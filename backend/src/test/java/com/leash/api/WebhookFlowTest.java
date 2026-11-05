package com.leash.api;

import com.leash.MutableClock;
import com.leash.ledger.LedgerRepository;
import com.leash.mandate.DemoLeashes;
import com.leash.mandate.MandateService;
import com.leash.paypal.FakePayPalServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** T15: PayPal webhooks (verified, deduplicated, state-conditional) and the Regret refund, over real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookFlowTest {

    static final FakePayPalServer fake = new FakePayPalServer();
    static final AtomicInteger eventSeq = new AtomicInteger();

    @DynamicPropertySource
    static void paypal(DynamicPropertyRegistry r) {
        r.add("leash.paypal.base-url", fake::baseUrl);
        r.add("leash.paypal.client-id", () -> "id");
        r.add("leash.paypal.client-secret", () -> "secret");
        r.add("leash.paypal.webhook-id", () -> "WH-TEST-1");
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    @Value("${local.server.port}")
    int port;

    @Autowired LedgerRepository repo;
    @Autowired MandateService leashes;
    @Autowired MutableClock clock;

    Http http;

    @BeforeEach
    void reset() {
        repo.deleteAll();
        DemoLeashes.all().forEach(leashes::seed);
        fake.reset();
        clock.reset();
        http = new Http(port);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    static final Map<String, String> SIGNED = Map.of(
            "PAYPAL-AUTH-ALGO", "SHA256withRSA",
            "PAYPAL-CERT-URL", "https://api.sandbox.paypal.com/v1/notifications/certs/CERT-1",
            "PAYPAL-TRANSMISSION-ID", "tx-1",
            "PAYPAL-TRANSMISSION-SIG", "sig==",
            "PAYPAL-TRANSMISSION-TIME", "2026-11-05T12:00:00Z");

    Map<String, Object> grocery(long amount) {
        var m = new LinkedHashMap<String, Object>();
        m.put("agentId", "agent-larder");
        m.put("merchant", "Larder");
        m.put("category", "Groceries");
        m.put("description", "Milk, eggs, bread");
        m.put("amountMinor", amount);
        m.put("currency", "GBP");
        m.put("task", "Buy this week's groceries");
        m.put("reason", "Weekly staples");
        return m;
    }

    /** An APPROVE that is waiting for the payer: returns {requestId, orderId}. */
    String[] awaiting(long amount) {
        var j = http.post("/api/purchases", Http.json(grocery(amount))).json();
        assertThat(j.get("status").asString()).isEqualTo("AWAITING_PAYER");
        return new String[]{j.get("requestId").asString(), j.get("paypalOrderId").asString()};
    }

    /** A paid purchase: returns {requestId, captureId}. */
    String[] paid(long amount) {
        String id = awaiting(amount)[0];
        var j = http.post("/api/ledger/" + id + "/capture", "").json();
        assertThat(j.get("status").asString()).isEqualTo("PAID");
        return new String[]{id, j.get("paypalCaptureId").asString()};
    }

    static String event(String id, String type, Map<String, Object> resource) {
        return Http.json(Map.of("id", id, "event_type", type, "resource_type", "capture", "resource", resource));
    }

    static String newEventId() {
        return "WH-" + eventSeq.incrementAndGet();
    }

    static Map<String, Object> amount(String value) {
        return Map.of("currency_code", "GBP", "value", value);
    }

    static Map<String, Object> up(String href) {
        return Map.of("href", href, "rel", "up", "method", "GET");
    }

    Http.Res deliver(String rawEvent) {
        return http.post("/api/paypal/webhook", rawEvent, SIGNED);
    }

    String status(String entryId) {
        return http.get("/api/ledger/" + entryId).json().get("status").asString();
    }

    // ── Verification ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void verifiesWithPayPalSendingTheEventBackUntouched() {
        // Odd spacing and a float-looking number: re-serialising would change these bytes.
        String raw = "{\"id\":\"WH-RAW\",  \"event_type\":\"BILLING.PLAN.CREATED\",\"resource\":{\"n\":1.10}}";
        var res = deliver(raw);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().get("result").asString()).contains("ignored");

        var verify = fake.last("POST", "/v1/notifications/verify-webhook-signature");
        assertThat(verify.body()).containsEntry("webhook_id", "WH-TEST-1")
                .containsEntry("auth_algo", "SHA256withRSA")
                .containsEntry("transmission_id", "tx-1")
                .containsEntry("transmission_sig", "sig==")
                .containsEntry("transmission_time", "2026-11-05T12:00:00Z")
                .containsEntry("cert_url", "https://api.sandbox.paypal.com/v1/notifications/certs/CERT-1");
        assertThat(verify.body().get("webhook_event")).isInstanceOf(Map.class);
    }

    @Test
    void unverifiedEventIsRejectedAndChangesNothing() {
        String[] e = awaiting(1_840);
        fake.webhookVerification = "FAILURE";
        var res = deliver(event(newEventId(), "CHECKOUT.ORDER.APPROVED", Map.of("id", e[1])));
        assertThat(res.status()).isEqualTo(400);
        assertThat(status(e[0])).isEqualTo("AWAITING_PAYER");
        assertThat(fake.count("POST", "/v2/checkout/orders/" + e[1] + "/capture")).isZero();
    }

    @Test
    void missingSignatureHeadersAreRejectedWithoutAskingPayPal() {
        var res = http.post("/api/paypal/webhook", event(newEventId(), "PAYMENT.CAPTURE.COMPLETED", Map.of("id", "X")));
        assertThat(res.status()).isEqualTo(400);
        assertThat(fake.count("POST", "/v1/notifications")).isZero();
    }

    @Test
    void notJsonIsRejected() {
        assertThat(deliver("not json").status()).isEqualTo(400);
    }

    // ── Events ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void orderApprovedCapturesWhenThePayerNeverCameBack() {
        String[] e = awaiting(1_840);
        var res = deliver(event(newEventId(), "CHECKOUT.ORDER.APPROVED", Map.of("id", e[1], "status", "APPROVED")));
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().get("entryId").asString()).isEqualTo(e[0]);
        assertThat(status(e[0])).isEqualTo("PAID");
    }

    @Test
    void orderApprovedForAnOrderLeashDidNotMakeIsIgnored() {
        var res = deliver(event(newEventId(), "CHECKOUT.ORDER.APPROVED", Map.of("id", "SOMEONE-ELSES")));
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().get("result").asString()).contains("not a Leash order");
        assertThat(fake.count("POST", "/v2/checkout/orders/SOMEONE-ELSES")).isZero();
    }

    @Test
    void captureCompletedMarksTheWaitingEntryPaidByItsOrderLink() {
        String[] e = awaiting(1_840);
        var capture = Map.<String, Object>of("id", "CAP-WH-1", "status", "COMPLETED", "amount", amount("18.40"),
                "links", List.of(up("https://api.sandbox.paypal.com/v2/checkout/orders/" + e[1])));
        var res = deliver(event(newEventId(), "PAYMENT.CAPTURE.COMPLETED", capture));
        assertThat(res.json().get("result").asString()).isEqualTo("marked paid");
        var entry = http.get("/api/ledger/" + e[0]).json();
        assertThat(entry.get("status").asString()).isEqualTo("PAID");
        assertThat(entry.get("paypalCaptureId").asString()).isEqualTo("CAP-WH-1");
    }

    @Test
    void captureCompletedWithTheWrongAmountIsNotTrusted() {
        String[] e = awaiting(1_840);
        var capture = Map.<String, Object>of("id", "CAP-WH-2", "status", "COMPLETED", "amount", amount("1.84"),
                "links", List.of(up("https://api.sandbox.paypal.com/v2/checkout/orders/" + e[1])));
        var res = deliver(event(newEventId(), "PAYMENT.CAPTURE.COMPLETED", capture));
        assertThat(res.json().get("result").asString()).contains("amount");
        assertThat(status(e[0])).isEqualTo("AWAITING_PAYER");
    }

    @Test
    void captureCompletedAfterTheReturnUrlAlreadyCapturedChangesNothing() {
        String[] p = paid(1_840);
        var capture = Map.<String, Object>of("id", p[1], "status", "COMPLETED", "amount", amount("18.40"));
        var res = deliver(event(newEventId(), "PAYMENT.CAPTURE.COMPLETED", capture));
        assertThat(res.json().get("result").asString()).contains("already PAID");
    }

    @Test
    void captureDeniedMarksThePaymentFailed() {
        String[] p = paid(1_840);
        var res = deliver(event(newEventId(), "PAYMENT.CAPTURE.DENIED", Map.of("id", p[1], "status", "DECLINED")));
        assertThat(res.json().get("result").asString()).isEqualTo("marked payment failed");
        assertThat(status(p[0])).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    void fullRefundFromThePayPalDashboardMarksRefundedAndFreesTheLength() {
        // £25 + £24 of a £60 week: another £20 only fits once the £25 is refunded.
        String[] p = paid(2_500);
        paid(2_400);

        var refund = Map.<String, Object>of("id", "REF-WH-1", "status", "COMPLETED", "amount", amount("25.00"),
                "links", List.of(up("https://api.sandbox.paypal.com/v2/payments/captures/" + p[1])));
        var res = deliver(event(newEventId(), "PAYMENT.CAPTURE.REFUNDED", refund));
        assertThat(res.json().get("result").asString()).isEqualTo("marked refunded");
        var entry = http.get("/api/ledger/" + p[0]).json();
        assertThat(entry.get("status").asString()).isEqualTo("REFUNDED");
        assertThat(entry.get("paypalRefundId").asString()).isEqualTo("REF-WH-1");

        assertThat(http.post("/api/purchases", Http.json(grocery(2_000))).json().get("outcome").asString()).isEqualTo("APPROVE");
    }

    @Test
    void partialRefundIsNotedButStaysPaid() {
        String[] p = paid(1_840);
        var refund = Map.<String, Object>of("id", "REF-WH-2", "status", "COMPLETED", "amount", amount("5.00"),
                "links", List.of(up("https://api.sandbox.paypal.com/v2/payments/captures/" + p[1])));
        var res = deliver(event(newEventId(), "PAYMENT.CAPTURE.REFUNDED", refund));
        assertThat(res.json().get("result").asString()).isEqualTo("noted partial refund");
        var entry = http.get("/api/ledger/" + p[0]).json();
        assertThat(entry.get("status").asString()).isEqualTo("PAID");
        assertThat(entry.get("statusDetail").asString()).contains("5.00", "£18.40");
    }

    @Test
    void theSameEventDeliveredTwiceIsAppliedOnce() {
        String[] e = awaiting(1_840);
        String raw = event(newEventId(), "CHECKOUT.ORDER.APPROVED", Map.of("id", e[1]));
        assertThat(deliver(raw).json().get("result").asString()).startsWith("captured");
        var again = deliver(raw);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().get("result").asString()).contains("duplicate");
        assertThat(fake.count("POST", "/v2/checkout/orders/" + e[1] + "/capture")).isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesOfOneEventDoNotFail() throws Exception {
        String[] p = paid(1_840);
        String raw = event(newEventId(), "PAYMENT.CAPTURE.DENIED", Map.of("id", p[1]));
        try (var pool = Executors.newFixedThreadPool(5)) {
            List<Callable<Integer>> calls = java.util.stream.IntStream.range(0, 5)
                    .<Callable<Integer>>mapToObj(i -> () -> deliver(raw).status()).toList();
            for (var f : pool.invokeAll(calls)) assertThat(f.get()).isEqualTo(200);
        }
        assertThat(status(p[0])).isEqualTo("PAYMENT_FAILED");
    }

    // ── Regret ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void regretRefundsInFullOnceAndFreesTheLength() {
        String[] p = paid(2_500);
        paid(2_400);
        var res = http.post("/api/ledger/" + p[0] + "/refund", "");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().get("status").asString()).isEqualTo("REFUNDED");
        assertThat(res.json().get("paypalRefundId").asString()).startsWith("REF-");
        assertThat(res.json().get("statusDetail").asString()).contains("£25.00");

        var call = fake.last("POST", "/v2/payments/captures/" + p[1] + "/refund");
        assertThat(call.header("PayPal-Request-Id")).isEqualTo("refund-" + p[0]);
        assertThat(call.body()).isEmpty();

        // A second click returns the same entry and doesn't ask PayPal again.
        assertThat(http.post("/api/ledger/" + p[0] + "/refund", "").json().get("status").asString()).isEqualTo("REFUNDED");
        assertThat(fake.count("POST", "/v2/payments/captures/")).isEqualTo(1);

        assertThat(http.post("/api/purchases", Http.json(grocery(2_000))).json().get("outcome").asString()).isEqualTo("APPROVE");
    }

    @Test
    void refundWebhookAfterRegretChangesNothing() {
        String[] p = paid(1_840);
        String refundId = http.post("/api/ledger/" + p[0] + "/refund", "").json().get("paypalRefundId").asString();
        var refund = Map.<String, Object>of("id", refundId, "status", "COMPLETED", "amount", amount("18.40"),
                "links", List.of(up("https://api.sandbox.paypal.com/v2/payments/captures/" + p[1])));
        assertThat(deliver(event(newEventId(), "PAYMENT.CAPTURE.REFUNDED", refund)).json().get("result").asString())
                .contains("already refunded");
    }

    @Test
    void pendingRefundStaysPaidUntilPayPalConfirms() {
        String[] p = paid(1_840);
        fake.refundStatus = "PENDING";
        var res = http.post("/api/ledger/" + p[0] + "/refund", "").json();
        assertThat(res.get("status").asString()).isEqualTo("PAID");
        assertThat(res.get("statusDetail").asString()).contains("PENDING");

        var refund = Map.<String, Object>of("id", res.get("paypalRefundId").asString(), "status", "COMPLETED",
                "amount", amount("18.40"), "links", List.of(up("https://api.sandbox.paypal.com/v2/payments/captures/" + p[1])));
        deliver(event(newEventId(), "PAYMENT.CAPTURE.REFUNDED", refund));
        assertThat(status(p[0])).isEqualTo("REFUNDED");
    }

    @Test
    void failedRefundIsAConflictAndStaysPaid() {
        String[] p = paid(1_840);
        fake.refundStatus = "FAILED";
        assertThat(http.post("/api/ledger/" + p[0] + "/refund", "").status()).isEqualTo(409);
        assertThat(status(p[0])).isEqualTo("PAID");
    }

    @Test
    void paypalErrorOnRefundIs502AndStaysPaid() {
        String[] p = paid(1_840);
        fake.failNextRefundWith = 422;
        assertThat(http.post("/api/ledger/" + p[0] + "/refund", "").status()).isEqualTo(502);
        assertThat(status(p[0])).isEqualTo("PAID");
    }

    @Test
    void onlyPaidPurchasesCanBeRefunded() {
        String[] e = awaiting(1_840);
        assertThat(http.post("/api/ledger/" + e[0] + "/refund", "").status()).isEqualTo(409);
        assertThat(fake.count("POST", "/v2/payments/captures/")).isZero();
        assertThat(http.post("/api/ledger/nope/refund", "").status()).isEqualTo(404);
    }
}
