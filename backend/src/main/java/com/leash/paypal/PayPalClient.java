package com.leash.paypal;

import com.leash.config.LeashProperties;
import com.leash.policy.Money;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin PayPal REST client for the sandbox.
 *
 * <p>Starter scope: OAuth token (cached), create order, capture order. T11 adds Vault (payment tokens), T15 adds
 * webhooks and refunds. Before adding a call, check the request/response shape at developer.paypal.com and link it.
 *
 * <p>Docs:
 * <ul>
 *   <li>OAuth: https://developer.paypal.com/api/rest/authentication/</li>
 *   <li>Orders v2: https://developer.paypal.com/docs/api/orders/v2/</li>
 * </ul>
 */
@Component
public class PayPalClient {

    private final LeashProperties.PayPal cfg;
    private final RestClient http;
    private final Clock clock;

    private volatile String token;
    private volatile Instant tokenExpires = Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public PayPalClient(LeashProperties props) {
        this(props.paypal(), RestClient.builder(), Clock.systemUTC());
    }

    PayPalClient(LeashProperties.PayPal cfg, RestClient.Builder builder, Clock clock) {
        this.cfg = cfg;
        this.clock = clock;
        if (cfg.baseUrl() != null && !cfg.isSandbox()) {
            throw new IllegalStateException("Leash only runs against the PayPal sandbox. Check LEASH_PAYPAL_BASE_URL.");
        }
        this.http = builder.baseUrl(cfg.baseUrl() == null ? "https://api-m.sandbox.paypal.com" : cfg.baseUrl()).build();
    }

    public boolean configured() {
        return cfg.configured();
    }

    /** Client-credentials token, reused until 60s before it expires. */
    synchronized String accessToken() {
        if (token != null && clock.instant().isBefore(tokenExpires.minusSeconds(60))) return token;
        String basic = Base64.getEncoder().encodeToString(
                (cfg.clientId() + ":" + cfg.clientSecret()).getBytes(StandardCharsets.UTF_8));
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = http.post()
                .uri("/v1/oauth2/token")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (body == null || body.get("access_token") == null) throw new PayPalException("No access token in PayPal response");
        token = (String) body.get("access_token");
        long expiresIn = ((Number) body.getOrDefault("expires_in", 300)).longValue();
        tokenExpires = clock.instant().plusSeconds(expiresIn);
        return token;
    }

    /**
     * Creates a CAPTURE order. {@code requestId} becomes the PayPal-Request-Id header, so retrying the same Leash
     * purchase can never create two orders.
     */
    public PayPalOrder createOrder(String requestId, long amountMinor, String currency, String description) {
        var unit = new java.util.LinkedHashMap<String, Object>();
        unit.put("reference_id", requestId);
        unit.put("description", truncate(description, 127));
        unit.put("amount", Map.of("currency_code", currency, "value", Money.toPayPalValue(amountMinor, currency)));
        if (cfg.payeeEmail() != null && !cfg.payeeEmail().isBlank()) {
            unit.put("payee", Map.of("email_address", cfg.payeeEmail()));
        }
        Map<String, Object> req = Map.of("intent", "CAPTURE", "purchase_units", List.of(unit));

        @SuppressWarnings("unchecked")
        Map<String, Object> res = http.post()
                .uri("/v2/checkout/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                .header("PayPal-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(Map.class);
        return PayPalOrder.from(res);
    }

    public PayPalOrder captureOrder(String orderId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> res = http.post()
                .uri("/v2/checkout/orders/{id}/capture", orderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(Map.class);
        return PayPalOrder.from(res);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** The parts of an order Leash keeps. Never store payer details. */
    public record PayPalOrder(String id, String status, Optional<String> approveUrl) {
        @SuppressWarnings("unchecked")
        static PayPalOrder from(Map<String, Object> res) {
            if (res == null) throw new PayPalException("Empty PayPal response");
            String approve = null;
            Object links = res.get("links");
            if (links instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> link) {
                        Object rel = link.get("rel");
                        if ("approve".equals(rel) || "payer-action".equals(rel)) approve = (String) link.get("href");
                    }
                }
            }
            return new PayPalOrder((String) res.get("id"), (String) res.get("status"), Optional.ofNullable(approve));
        }
    }
}
