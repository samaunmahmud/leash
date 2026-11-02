package com.leash.paypal;

import com.leash.config.LeashProperties;
import com.leash.policy.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Thin PayPal REST client for the sandbox: OAuth (cached), Orders v2 create/capture/get. T11 adds Vault, T15 adds
 * refunds and webhook verification.
 *
 * <p>Every field sent or read here was checked against PayPal's published OpenAPI specs (linked above each call).
 * Logging rule: order IDs and statuses only. Never tokens, secrets or anything about the payer.
 */
@Component
public class PayPalClient {

    private static final Logger log = LoggerFactory.getLogger(PayPalClient.class);
    static final String SANDBOX = "https://api-m.sandbox.paypal.com";

    private final LeashProperties.PayPal cfg;
    private final String publicUrl;
    private final RestClient http;
    private final Clock clock;

    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    @Autowired
    public PayPalClient(LeashProperties props) {
        this(props.paypal(), props.publicUrl(), RestClient.builder(), Clock.systemUTC());
    }

    PayPalClient(LeashProperties.PayPal cfg, String publicUrl, RestClient.Builder builder, Clock clock) {
        if (!cfg.isSandbox()) {
            throw new IllegalStateException("Leash only runs against the PayPal sandbox. Check LEASH_PAYPAL_BASE_URL.");
        }
        this.cfg = cfg;
        this.publicUrl = publicUrl;
        this.clock = clock;
        String base = cfg.baseUrl() == null || cfg.baseUrl().isBlank() ? SANDBOX : cfg.baseUrl();
        this.http = builder.baseUrl(base).build();
    }

    public boolean configured() {
        return cfg.configured();
    }

    // ── OAuth ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Client-credentials token, reused until 60s before it expires so a request never goes out with a token that
     * dies in flight. Synchronized so concurrent purchases share one token fetch.
     *
     * <p>Docs: https://developer.paypal.com/api/rest/authentication/ (POST /v1/oauth2/token, Basic auth,
     * grant_type=client_credentials → access_token, expires_in seconds)
     */
    synchronized String accessToken() {
        if (!configured()) throw new PayPalException("PayPal isn't configured. Add sandbox credentials to .env.");
        if (token != null && clock.instant().isBefore(tokenExpires.minusSeconds(60))) return token;
        String basic = Base64.getEncoder().encodeToString(
                (cfg.clientId() + ":" + cfg.clientSecret()).getBytes(StandardCharsets.UTF_8));
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        Map<String, Object> body = call("OAuth token", () -> http.post()
                .uri("/v1/oauth2/token")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(MAP));
        if (body == null || !(body.get("access_token") instanceof String t)) {
            throw new PayPalException("PayPal returned no access token");
        }
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 300;
        token = t;
        tokenExpires = clock.instant().plusSeconds(expiresIn);
        log.info("PayPal OAuth token refreshed, valid for {}s", expiresIn);
        return token;
    }

    // ── Orders v2 ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a CAPTURE order the payer approves in PayPal's checkout. {@code requestId} is sent as
     * PayPal-Request-Id, so retrying the same Leash purchase returns the same order instead of a second one.
     *
     * <p>Docs: https://developer.paypal.com/docs/api/orders/v2/#orders_create
     * Request: intent, purchase_units[].{reference_id, description, amount.{currency_code, value}, payee.email_address},
     * payment_source.paypal.experience_context.{brand_name, user_action, shipping_preference, return_url, cancel_url}.
     * Response: id, status, links[] (rel "payer-action" when payment_source.paypal is sent).
     */
    public PayPalOrder createOrder(String requestId, long amountMinor, String currency, String description) {
        var experience = new LinkedHashMap<String, Object>();
        experience.put("brand_name", "Leash");
        experience.put("user_action", "PAY_NOW");
        experience.put("shipping_preference", "NO_SHIPPING");
        experience.put("return_url", publicUrl + "/api/paypal/return");
        experience.put("cancel_url", publicUrl + "/api/paypal/cancel");
        Map<String, Object> req = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(purchaseUnit(requestId, amountMinor, currency, description)),
                "payment_source", Map.of("paypal", Map.of("experience_context", experience)));
        PayPalOrder order = postOrder("/v2/checkout/orders", requestId, req, "create order");
        log.info("PayPal order {} created for request {} ({})", order.id(), requestId, order.status());
        return order;
    }

    /**
     * Captures an order the payer has approved. Sent with a PayPal-Request-Id derived from the order, so a retried
     * capture can't charge twice.
     *
     * <p>Docs: https://developer.paypal.com/docs/api/orders/v2/#orders_capture
     * Response: status, purchase_units[].payments.captures[].{id, status}.
     */
    public PayPalOrder captureOrder(String orderId) {
        Map<String, Object> res = call("capture order", () -> http.post()
                .uri("/v2/checkout/orders/{id}/capture", orderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                .header("PayPal-Request-Id", "capture-" + orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(MAP));
        PayPalOrder order = PayPalOrder.from(res);
        log.info("PayPal order {} captured: {} (capture {} {})", order.id(), order.status(), order.captureId(), order.captureStatus());
        return order;
    }

    /** Docs: https://developer.paypal.com/docs/api/orders/v2/#orders_get */
    public PayPalOrder getOrder(String orderId) {
        return PayPalOrder.from(call("get order", () -> http.get()
                .uri("/v2/checkout/orders/{id}", orderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                .retrieve()
                .body(MAP)));
    }

    Map<String, Object> purchaseUnit(String requestId, long amountMinor, String currency, String description) {
        var unit = new LinkedHashMap<String, Object>();
        unit.put("reference_id", truncate(requestId, 256));
        unit.put("description", truncate(description, 127));
        unit.put("amount", Map.of("currency_code", currency, "value", Money.toPayPalValue(amountMinor, currency)));
        if (cfg.payeeEmail() != null && !cfg.payeeEmail().isBlank()) {
            unit.put("payee", Map.of("email_address", cfg.payeeEmail()));
        }
        return unit;
    }

    PayPalOrder postOrder(String path, String requestId, Map<String, Object> body, String what) {
        return PayPalOrder.from(call(what, () -> http.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                .header("PayPal-Request-Id", requestId)
                // Ask for the full order back so captures and vault details are in the create response too.
                .header("Prefer", "return=representation")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MAP)));
    }

    RestClient http() {
        return http;
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────────────────

    static final org.springframework.core.ParameterizedTypeReference<Map<String, Object>> MAP =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    /**
     * Runs a call and turns failures into a PayPalException that carries PayPal's error name and debug_id, but
     * never the body (it can contain payer details) and never our credentials.
     */
    <T> T call(String what, Supplier<T> fn) {
        try {
            return fn.get();
        } catch (RestClientResponseException e) {
            String name = null;
            String debugId = null;
            String issue = null;
            try {
                Map<String, Object> err = e.getResponseBodyAs(MAP);
                if (err != null) {
                    name = str(err.get("name"));
                    if (name == null) name = str(err.get("error")); // OAuth errors use "error"
                    debugId = str(err.get("debug_id"));
                    if (err.get("details") instanceof List<?> d && !d.isEmpty() && d.getFirst() instanceof Map<?, ?> first) {
                        issue = str(first.get("issue"));
                    }
                }
            } catch (RuntimeException ignored) {
                // Not JSON: keep the status only.
            }
            String msg = "PayPal " + what + " failed: HTTP " + e.getStatusCode().value()
                    + (name != null ? " " + name : "") + (issue != null ? " (" + issue + ")" : "")
                    + (debugId != null ? " debug_id=" + debugId : "");
            log.warn(msg);
            throw new PayPalException(msg, e.getStatusCode().value(), issue != null ? issue : name, e);
        } catch (ResourceAccessException e) {
            log.warn("PayPal {} failed: unreachable", what);
            throw new PayPalException("PayPal " + what + " failed: PayPal unreachable", 0, null, e);
        }
    }

    static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * The parts of an order Leash keeps. Deliberately has no payer fields, so they can't leak into the ledger.
     *
     * @param approveUrl    where the payer approves (rel "approve" or "payer-action"), if the order needs it
     * @param captureId     the first capture's ID once money has moved (needed for refunds)
     * @param captureStatus COMPLETED, PENDING, DECLINED, REFUNDED, ...
     * @param vaultId       payment_source.paypal.attributes.vault.id when PayPal saved the payer's wallet (T11)
     */
    public record PayPalOrder(String id, String status, Optional<String> approveUrl, String captureId,
                              String captureStatus, String vaultId) {

        public boolean completed() {
            return "COMPLETED".equals(status);
        }

        static PayPalOrder from(Map<String, Object> res) {
            if (res == null || str(res.get("id")) == null) throw new PayPalException("Empty PayPal order response");
            String approve = null;
            if (res.get("links") instanceof List<?> links) {
                for (Object o : links) {
                    if (o instanceof Map<?, ?> link) {
                        Object rel = link.get("rel");
                        if ("approve".equals(rel) || "payer-action".equals(rel)) approve = str(link.get("href"));
                    }
                }
            }
            String captureId = null;
            String captureStatus = null;
            if (res.get("purchase_units") instanceof List<?> units) {
                for (Object u : units) {
                    if (u instanceof Map<?, ?> unit && unit.get("payments") instanceof Map<?, ?> payments
                            && payments.get("captures") instanceof List<?> captures && !captures.isEmpty()
                            && captures.getFirst() instanceof Map<?, ?> cap) {
                        captureId = str(cap.get("id"));
                        captureStatus = str(cap.get("status"));
                        break;
                    }
                }
            }
            String vaultId = null;
            if (res.get("payment_source") instanceof Map<?, ?> ps && ps.get("paypal") instanceof Map<?, ?> pp
                    && pp.get("attributes") instanceof Map<?, ?> attrs && attrs.get("vault") instanceof Map<?, ?> vault) {
                vaultId = str(vault.get("id"));
            }
            return new PayPalOrder(str(res.get("id")), str(res.get("status")), Optional.ofNullable(approve),
                    captureId, captureStatus, vaultId);
        }
    }
}
