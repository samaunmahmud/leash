package com.leash.paypal;

import com.leash.config.LeashProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayPalClientTest {

    static final FakePayPalServer fake = new FakePayPalServer();

    /** A clock tests can move forward. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-09T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    MutableClock clock;
    PayPalClient client;

    @BeforeEach
    void setUp() {
        fake.reset();
        clock = new MutableClock();
        client = client(fake.baseUrl(), "biz@example.com");
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    PayPalClient client(String base, String payee) {
        var cfg = new LeashProperties.PayPal(base, "client-id", "client-secret", payee, null);
        return new PayPalClient(cfg, "http://localhost:8080", RestClient.builder(), clock);
    }

    @Test
    void reusesTheOAuthTokenAcrossCalls() {
        client.createOrder("r1", 1_840, "GBP", "milk");
        client.createOrder("r2", 900, "GBP", "eggs");
        assertThat(fake.count("POST", "/v1/oauth2/token")).isEqualTo(1);
        assertThat(fake.last("POST", "/v1/oauth2/token").header("Authorization")).startsWith("Basic ");
    }

    @Test
    void refreshesTheTokenSixtySecondsBeforeItExpires() {
        fake.tokenExpiresIn = 600;
        client.accessToken();
        clock.advance(Duration.ofSeconds(539)); // 61s left: still good
        client.accessToken();
        assertThat(fake.count("POST", "/v1/oauth2/token")).isEqualTo(1);

        clock.advance(Duration.ofSeconds(2)); // 59s left: refresh
        client.accessToken();
        assertThat(fake.count("POST", "/v1/oauth2/token")).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createSendsIdempotencyKeyAmountAndPayee() {
        var order = client.createOrder("req-123", 1_840, "GBP", "Milk, eggs, bread");

        var sent = fake.last("POST", "/v2/checkout/orders");
        assertThat(sent.header("PayPal-Request-Id")).isEqualTo("req-123");
        assertThat(sent.header("Authorization")).startsWith("Bearer A21AA-fake-");
        assertThat(sent.body().get("intent")).isEqualTo("CAPTURE");
        var unit = ((List<Map<String, Object>>) sent.body().get("purchase_units")).getFirst();
        assertThat(unit.get("reference_id")).isEqualTo("req-123");
        assertThat(unit.get("amount")).isEqualTo(Map.of("currency_code", "GBP", "value", "18.40"));
        assertThat(unit.get("payee")).isEqualTo(Map.of("email_address", "biz@example.com"));
        var ctx = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) sent.body().get("payment_source"))
                .get("paypal")).get("experience_context");
        assertThat(ctx.get("return_url")).isEqualTo("http://localhost:8080/api/paypal/return");
        assertThat(ctx.get("cancel_url")).isEqualTo("http://localhost:8080/api/paypal/cancel");

        assertThat(order.id()).startsWith("ORDER-");
        assertThat(order.status()).isEqualTo("PAYER_ACTION_REQUIRED");
        assertThat(order.approveUrl()).hasValueSatisfying(u -> assertThat(u).contains("sandbox.paypal.com"));
    }

    @Test
    void retryingWithTheSameRequestIdReturnsTheSameOrder() {
        var first = client.createOrder("same", 500, "GBP", "x");
        var second = client.createOrder("same", 500, "GBP", "x");
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void omitsPayeeWhenNotConfigured() {
        client(fake.baseUrl(), "").createOrder("r", 500, "GBP", "x");
        @SuppressWarnings("unchecked")
        var unit = ((List<Map<String, Object>>) fake.last("POST", "/v2/checkout/orders").body().get("purchase_units")).getFirst();
        assertThat(unit).doesNotContainKey("payee");
    }

    @Test
    void captureReturnsCaptureIdAndSendsItsOwnIdempotencyKey() {
        var order = client.createOrder("r", 3_200, "GBP", "big shop");
        var captured = client.captureOrder(order.id());

        assertThat(captured.completed()).isTrue();
        assertThat(captured.captureId()).startsWith("CAP-");
        assertThat(captured.captureStatus()).isEqualTo("COMPLETED");
        assertThat(fake.last("POST", "/v2/checkout/orders/" + order.id() + "/capture").header("PayPal-Request-Id"))
                .isEqualTo("capture-" + order.id());
    }

    @Test
    void errorsCarryPayPalsIssueAndDebugIdButNotTheBody() {
        fake.failNextCreateWith = 422;
        assertThatThrownBy(() -> client.createOrder("r", 500, "GBP", "x"))
                .isInstanceOf(PayPalException.class)
                .hasMessageContaining("HTTP 422")
                .hasMessageContaining("PAYEE_ACCOUNT_RESTRICTED")
                .hasMessageContaining("dbg123")
                .hasMessageNotContaining("secret payer stuff")
                .satisfies(e -> assertThat(((PayPalException) e).status()).isEqualTo(422));
    }

    @Test
    void captureOfUnapprovedOrderFailsCleanly() {
        fake.requireApprovalBeforeCapture = true;
        var order = client.createOrder("r", 500, "GBP", "x");
        assertThatThrownBy(() -> client.captureOrder(order.id()))
                .isInstanceOf(PayPalException.class)
                .hasMessageContaining("ORDER_NOT_APPROVED");
        fake.approve(order.id());
        assertThat(client.captureOrder(order.id()).completed()).isTrue();
    }

    @Test
    void refusesToStartAgainstLivePayPal() {
        assertThatThrownBy(() -> client("https://api-m.paypal.com", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sandbox");
        assertThatThrownBy(() -> client("https://api-m.paypal.com/?sandbox=1", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unconfiguredClientNeverCallsPayPal() {
        var cfg = new LeashProperties.PayPal(fake.baseUrl(), "", "", null, null);
        var c = new PayPalClient(cfg, "http://localhost:8080", RestClient.builder(), clock);
        assertThat(c.configured()).isFalse();
        assertThatThrownBy(() -> c.createOrder("r", 500, "GBP", "x")).isInstanceOf(PayPalException.class);
        assertThat(fake.requests()).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void masksTheSavedEmailToAHint() {
        org.assertj.core.api.Assertions.assertThat(PayPalClient.maskEmail("sam@personal.example.com")).isEqualTo("s•••@personal.example.com");
        org.assertj.core.api.Assertions.assertThat(PayPalClient.maskEmail("nobody")).isEqualTo("•••");
        org.assertj.core.api.Assertions.assertThat(PayPalClient.maskEmail(null)).isNull();
    }
}
