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
 * Thin PayPal REST client for the sandbox: OAuth (cached), Orders v2 create/capture/get, Vault v3 (save the owner's
 * wallet once, then pay with no popup). T15 adds
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

    // ── Vault v3: save the owner's PayPal once, then pay with no popup ─────────────────────────────────────

    /**
     * Starts saving the owner's PayPal wallet without a purchase. The owner opens the returned approve link once.
     *
     * <p>Docs: https://developer.paypal.com/docs/checkout/save-payment-methods/purchase-later/payment-tokens-api/paypal/
     * and https://developer.paypal.com/docs/api/payment-tokens/v3/#setup-tokens_create (POST /v3/vault/setup-tokens).
     * Request: payment_source.paypal.{usage_type MERCHANT|PLATFORM, customer_type CONSUMER|BUSINESS,
     * permit_multiple_payment_tokens, usage_pattern, experience_context.{brand_name, shipping_preference, return_url,
     * cancel_url}} (fields checked against vault_payment_tokens_v3.json in paypal/paypal-rest-api-specifications).
     * Response: id, status (CREATED, PAYER_ACTION_REQUIRED, APPROVED, VAULTED, TOKENIZED), links[] with rel "approve".
     */
    public SetupToken createSetupToken(String requestId) {
        var experience = new LinkedHashMap<String, Object>();
        experience.put("brand_name", "Leash");
        experience.put("shipping_preference", "NO_SHIPPING");
        experience.put("return_url", publicUrl + "/api/paypal/vault/return");
        experience.put("cancel_url", publicUrl + "/api/paypal/vault/cancel");
        var paypal = new LinkedHashMap<String, Object>();
        paypal.put("usage_type", "MERCHANT");
        paypal.put("customer_type", "CONSUMER");
        paypal.put("permit_multiple_payment_tokens", false);
        paypal.put("usage_pattern", "IMMEDIATE");
        paypal.put("experience_context", experience);
        Map<String, Object> res = call("create setup token", () -> http.post()
                .uri("/v3/vault/setup-tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                .header("PayPal-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("payment_source", Map.of("paypal", paypal)))
                .retrieve()
                .body(MAP));
        if (res == null || str(res.get("id")) == null) throw new PayPalException("Empty PayPal setup token response");
        var token = new SetupToken(str(res.get("id")), str(res.get("status")), link(res, "approve"));
        log.info("PayPal setup token {} created ({})", token.id(), token.status());
        return token;
    }

    /**
     * Turns the approved setup token into a payment token (the vault ID). Only the ID and a masked email hint leave
     * this method: the email, payer ID and customer ID in the response are dropped here.
     *
     * <p>Docs: https://developer.paypal.com/docs/api/payment-tokens/v3/#payment-tokens_create
     * (POST /v3/vault/payment-tokens). Request: payment_source.token.{id, type SETUP_TOKEN}.
     * Response: id, customer.id, payment_source.paypal.{email_address, payer_id, ...}.
     */
    public PaymentToken createPaymentToken(String setupTokenId) {
        Map<String, Object> res = call("create payment token", () -> http.post()
                .uri("/v3/vault/payment-tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                // Unique per attempt: a fixed key could make PayPal replay the "not approved yet" 422 after the owner
                // approves. Double-saving is prevented by WalletService instead.
                .header("PayPal-Request-Id", "vault-" + setupTokenId + "-" + java.util.UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("payment_source", Map.of("token", Map.of("id", setupTokenId, "type", "SETUP_TOKEN"))))
                .retrieve()
                .body(MAP));
        if (res == null || str(res.get("id")) == null) throw new PayPalException("Empty PayPal payment token response");
        String email = null;
        if (res.get("payment_source") instanceof Map<?, ?> ps && ps.get("paypal") instanceof Map<?, ?> pp) {
            email = str(pp.get("email_address"));
        }
        log.info("PayPal payment token {} saved", res.get("id"));
        return new PaymentToken(str(res.get("id")), maskEmail(email));
    }

    /**
     * Deletes a saved payment token, so "forget my PayPal" really forgets it at PayPal too.
     *
     * <p>Docs: https://developer.paypal.com/docs/api/payment-tokens/v3/#payment-tokens_delete
     * (DELETE /v3/vault/payment-tokens/{id}, 204 No Content).
     */
    public void deletePaymentToken(String paymentTokenId) {
        call("delete payment token", () -> http.delete()
                .uri("/v3/vault/payment-tokens/{id}", paymentTokenId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                .retrieve()
                .toBodilessEntity());
        log.info("PayPal payment token {} deleted", paymentTokenId);
    }

    /**
     * Pays an approved purchase with the saved wallet: no buyer redirect. Merchant-initiated (the agent buys while the
     * owner is away), so stored_credential says so.
     *
     * <p>Docs: https://developer.paypal.com/docs/checkout/save-payment-methods/purchase-later/payment-tokens-api/paypal/
     * ("Use payment token": POST /v2/checkout/orders with payment_source.paypal.vault_id; the sample response is
     * status COMPLETED with purchase_units[].payments.captures[]) and
     * https://developer.paypal.com/docs/api/orders/v2/#orders_create (payment_source.paypal.stored_credential.
     * {payment_initiator CUSTOMER|MERCHANT (required), usage FIRST|SUBSEQUENT|DERIVED}).
     * The PayPal-Request-Id is prefixed so it can never collide with an approve-link order for the same request.
     */
    public PayPalOrder createVaultOrder(String requestId, String vaultId, long amountMinor, String currency,
                                        String description) {
        var paypal = new LinkedHashMap<String, Object>();
        paypal.put("vault_id", vaultId);
        paypal.put("stored_credential", Map.of("payment_initiator", "MERCHANT", "usage", "SUBSEQUENT"));
        Map<String, Object> req = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(purchaseUnit(requestId, amountMinor, currency, description)),
                "payment_source", Map.of("paypal", paypal));
        PayPalOrder order = postOrder("/v2/checkout/orders", "vault-" + requestId, req, "create vault order");
        log.info("PayPal vault order {} for request {} ({}, capture {})", order.id(), requestId, order.status(), order.captureId());
        return order;
    }

    /** "sam@personal.example.com" → "s•••@personal.example.com". Null stays null. */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "•••";
        return email.charAt(0) + "•••" + email.substring(at);
    }

    private static String link(Map<String, Object> res, String rel) {
        if (res.get("links") instanceof List<?> links) {
            for (Object o : links) {
                if (o instanceof Map<?, ?> l && rel.equals(l.get("rel"))) return str(l.get("href"));
            }
        }
        return null;
    }

    /** A setup token waiting for the owner. Carries no payer data. */
    public record SetupToken(String id, String status, String approveUrl) {}

    /** The saved wallet as Leash keeps it: PayPal's vault ID and a masked hint, nothing else. */
    public record PaymentToken(String id, String hint) {}

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
