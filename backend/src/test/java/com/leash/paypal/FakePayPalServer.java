package com.leash.paypal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local HTTP server that answers like the PayPal sandbox for the calls Leash makes. Tests point
 * leash.paypal.base-url at it, so the real client code (headers, JSON, error handling) runs without touching PayPal.
 *
 * <p>Shapes follow PayPal's OpenAPI specs (developer.paypal.com/api/orders/v2/schema.json and friends), trimmed to
 * the fields Leash reads.
 */
public final class FakePayPalServer implements AutoCloseable {

    public record Recorded(String method, String path, Map<String, String> headers, Map<String, Object> body) {
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Map<String, Object>> ordersByRequestId = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> orders = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    public volatile int tokenExpiresIn = 32_400;
    /** When set, the next create-order call fails with this HTTP status (then resets). */
    public volatile int failNextCreateWith = 0;
    /** Capture fails with 422 ORDER_NOT_APPROVED unless the order was approved via {@link #approve}. */
    public volatile boolean requireApprovalBeforeCapture = false;
    public volatile String webhookVerification = "SUCCESS";
    /** null: payment-token creation succeeds. false: fails as if the owner never approved the setup token. */
    public volatile Boolean setupApproval = null;
    /** When set, the next vault order fails with this HTTP status (then resets). */
    public volatile int failNextVaultOrderWith = 0;
    /** Status the next new refund gets (COMPLETED, PENDING, FAILED, CANCELLED). */
    public volatile String refundStatus = "COMPLETED";
    /** When set, the next refund call fails with this HTTP status (then resets). */
    public volatile int failNextRefundWith = 0;
    private final Map<String, Map<String, Object>> refundsByRequestId = new ConcurrentHashMap<>();

    public FakePayPalServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public List<Recorded> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public long count(String method, String pathPrefix) {
        return requests().stream().filter(r -> r.method().equals(method) && r.path().startsWith(pathPrefix)).count();
    }

    public Recorded last(String method, String pathPrefix) {
        List<Recorded> all = requests();
        for (int i = all.size() - 1; i >= 0; i--) {
            Recorded r = all.get(i);
            if (r.method().equals(method) && r.path().startsWith(pathPrefix)) return r;
        }
        throw new AssertionError("No " + method + " " + pathPrefix + " recorded");
    }

    /** Simulates the buyer clicking "Pay" on the approve link. */
    public void approve(String orderId) {
        Map<String, Object> o = orders.get(orderId);
        if (o != null) o.put("status", "APPROVED");
    }

    public void reset() {
        requests.clear();
        orders.clear();
        ordersByRequestId.clear();
        failNextCreateWith = 0;
        requireApprovalBeforeCapture = false;
        webhookVerification = "SUCCESS";
        tokenExpiresIn = 32_400;
        setupApproval = null;
        failNextVaultOrderWith = 0;
        refundStatus = "COMPLETED";
        failNextRefundWith = 0;
        refundsByRequestId.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ── Routing ─────────────────────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        byte[] raw = ex.getRequestBody().readAllBytes();
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.isEmpty() ? "" : v.getFirst()));
        Map<String, Object> body = Map.of();
        String ct = headers.getOrDefault("content-type", "");
        if (raw.length > 0 && ct.contains("json")) body = JSON.readValue(raw, Map.class);
        requests.add(new Recorded(method, path, headers, body));

        try {
            if (path.equals("/v1/oauth2/token")) {
                if (!headers.getOrDefault("authorization", "").startsWith("Basic ")) {
                    send(ex, 401, Map.of("error", "invalid_client", "error_description", "Client Authentication failed"));
                    return;
                }
                send(ex, 200, Map.of("access_token", "A21AA-fake-" + seq.incrementAndGet(), "token_type", "Bearer",
                        "expires_in", tokenExpiresIn));
                return;
            }
            if (!headers.getOrDefault("authorization", "").startsWith("Bearer ")) {
                send(ex, 401, Map.of("name", "AUTHENTICATION_FAILURE", "message", "no token"));
                return;
            }
            if (method.equals("POST") && path.equals("/v2/checkout/orders")) {
                createOrder(ex, headers.get("paypal-request-id"), body);
            } else if (method.equals("POST") && path.matches("/v2/checkout/orders/[^/]+/capture")) {
                capture(ex, path.split("/")[4]);
            } else if (method.equals("GET") && path.matches("/v2/checkout/orders/[^/]+")) {
                Map<String, Object> o = orders.get(path.split("/")[4]);
                if (o == null) notFound(ex);
                else send(ex, 200, o);
            } else if (method.equals("POST") && path.matches("/v2/payments/captures/[^/]+/refund")) {
                refund(ex, path.split("/")[4], headers.get("paypal-request-id"));
            } else if (method.equals("POST") && path.equals("/v3/vault/setup-tokens")) {
                String id = "SETUP-" + seq.incrementAndGet();
                send(ex, 201, Map.of("id", id, "status", "PAYER_ACTION_REQUIRED", "links", List.of(
                        Map.of("href", "https://www.sandbox.paypal.com/agreements/approve?approval_session_id=" + id, "rel", "approve", "method", "GET"))));
            } else if (method.equals("POST") && path.equals("/v3/vault/payment-tokens")) {
                // Shape from the Vault v3 spec: id, customer.id, payment_source.paypal.{email_address, payer_id, ...}.
                // Payer fields are included so tests can prove Leash keeps none of them.
                if (setupApproval != null && !setupApproval) {
                    send(ex, 422, Map.of("name", "UNPROCESSABLE_ENTITY", "debug_id", "dbgvault",
                            "details", List.of(Map.of("issue", "SETUP_TOKEN_NOT_APPROVED"))));
                    return;
                }
                send(ex, 201, Map.of("id", "VAULT-" + seq.incrementAndGet(), "customer", Map.of("id", "CUST-1"),
                        "payment_source", Map.of("paypal", Map.of("email_address", "sam@personal.example.com",
                                "payer_id", "PAYERID123", "name", Map.of("given_name", "Sam", "surname", "Owner")))));
            } else if (method.equals("DELETE") && path.matches("/v3/vault/payment-tokens/[^/]+")) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
            } else if (method.equals("POST") && path.equals("/v1/notifications/verify-webhook-signature")) {
                send(ex, 200, Map.of("verification_status", webhookVerification));
            } else {
                notFound(ex);
            }
        } catch (RuntimeException e) {
            send(ex, 500, Map.of("name", "INTERNAL_SERVER_ERROR", "message", String.valueOf(e.getMessage())));
        }
    }

    @SuppressWarnings("unchecked")
    private void createOrder(HttpExchange ex, String requestId, Map<String, Object> body) throws IOException {
        if (failNextCreateWith != 0) {
            int status = failNextCreateWith;
            failNextCreateWith = 0;
            send(ex, status, Map.of("name", "UNPROCESSABLE_ENTITY", "debug_id", "dbg123",
                    "details", List.of(Map.of("issue", "PAYEE_ACCOUNT_RESTRICTED", "description", "secret payer stuff"))));
            return;
        }
        if (requestId != null && ordersByRequestId.containsKey(requestId)) {
            send(ex, 200, ordersByRequestId.get(requestId));
            return;
        }
        String id = "ORDER-" + seq.incrementAndGet();
        Map<String, Object> ps = (Map<String, Object>) body.getOrDefault("payment_source", Map.of());
        Map<String, Object> paypal = (Map<String, Object>) ps.getOrDefault("paypal", Map.of());
        List<Map<String, Object>> units = (List<Map<String, Object>>) body.get("purchase_units");

        Map<String, Object> order = new ConcurrentHashMap<>();
        order.put("id", id);
        order.put("intent", body.get("intent"));
        order.put("purchase_units", units);
        if (paypal.get("vault_id") != null && failNextVaultOrderWith != 0) {
            int status = failNextVaultOrderWith;
            failNextVaultOrderWith = 0;
            send(ex, status, Map.of("name", "UNPROCESSABLE_ENTITY", "debug_id", "dbgdecl",
                    "details", List.of(Map.of("issue", "INSTRUMENT_DECLINED"))));
            return;
        }
        if (paypal.get("vault_id") != null) {
            // A saved wallet: PayPal completes the order in the create call, no buyer redirect.
            order.put("status", "COMPLETED");
            order.put("purchase_units", List.of(withCapture(units.getFirst(), "CAP-" + seq.incrementAndGet())));
        } else if (!paypal.isEmpty()) {
            order.put("status", "PAYER_ACTION_REQUIRED");
            order.put("links", List.of(
                    Map.of("href", baseUrl() + "/v2/checkout/orders/" + id, "rel", "self", "method", "GET"),
                    Map.of("href", "https://www.sandbox.paypal.com/checkoutnow?token=" + id, "rel", "payer-action", "method", "GET")));
        } else {
            order.put("status", "CREATED");
            order.put("links", List.of(
                    Map.of("href", "https://www.sandbox.paypal.com/checkoutnow?token=" + id, "rel", "approve", "method", "GET")));
        }
        orders.put(id, order);
        if (requestId != null) ordersByRequestId.put(requestId, order);
        send(ex, 201, order);
    }

    @SuppressWarnings("unchecked")
    private void capture(HttpExchange ex, String id) throws IOException {
        Map<String, Object> o = orders.get(id);
        if (o == null) {
            notFound(ex);
            return;
        }
        if ("COMPLETED".equals(o.get("status"))) {
            send(ex, 422, Map.of("name", "UNPROCESSABLE_ENTITY", "details", List.of(Map.of("issue", "ORDER_ALREADY_CAPTURED"))));
            return;
        }
        if (requireApprovalBeforeCapture && !"APPROVED".equals(o.get("status"))) {
            send(ex, 422, Map.of("name", "UNPROCESSABLE_ENTITY", "details", List.of(Map.of("issue", "ORDER_NOT_APPROVED"))));
            return;
        }
        List<Map<String, Object>> units = (List<Map<String, Object>>) o.get("purchase_units");
        o.put("status", "COMPLETED");
        o.put("purchase_units", List.of(withCapture(units.getFirst(), "CAP-" + seq.incrementAndGet())));
        // Real responses include payer details; include some so tests can prove Leash never stores them.
        o.put("payer", Map.of("email_address", "buyer@example.com", "payer_id", "PAYERID123"));
        send(ex, 201, o);
    }

    /** Shape from payments_payment_v2.json: id, status, links[] with rel "up" = the capture. Same request ID → same refund. */
    private void refund(HttpExchange ex, String captureId, String requestId) throws IOException {
        if (failNextRefundWith != 0) {
            int status = failNextRefundWith;
            failNextRefundWith = 0;
            // 403 is what the real sandbox answered when the payee was another (personal) account.
            send(ex, status, status == 403
                    ? Map.of("name", "NOT_AUTHORIZED", "debug_id", "dbgref",
                            "details", List.of(Map.of("issue", "PERMISSION_DENIED")))
                    : Map.of("name", "UNPROCESSABLE_ENTITY", "debug_id", "dbgref",
                            "details", List.of(Map.of("issue", "CAPTURE_FULLY_REFUNDED"))));
            return;
        }
        if (requestId != null && refundsByRequestId.containsKey(requestId)) {
            send(ex, 200, refundsByRequestId.get(requestId));
            return;
        }
        String id = "REF-" + seq.incrementAndGet();
        Map<String, Object> r = Map.of("id", id, "status", refundStatus, "links", List.of(
                Map.of("href", baseUrl() + "/v2/payments/refunds/" + id, "rel", "self", "method", "GET"),
                Map.of("href", baseUrl() + "/v2/payments/captures/" + captureId, "rel", "up", "method", "GET")));
        if (requestId != null) refundsByRequestId.put(requestId, r);
        send(ex, 201, r);
    }

    private static Map<String, Object> withCapture(Map<String, Object> unit, String captureId) {
        Map<String, Object> u = new LinkedHashMap<>(unit);
        u.put("payments", Map.of("captures", List.of(Map.of("id", captureId, "status", "COMPLETED", "amount", unit.get("amount")))));
        return u;
    }

    private void notFound(HttpExchange ex) throws IOException {
        send(ex, 404, Map.of("name", "RESOURCE_NOT_FOUND", "debug_id", "dbg404"));
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] out = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }
}
