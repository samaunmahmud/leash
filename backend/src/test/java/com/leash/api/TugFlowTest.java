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

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TugFlowTest {

    static final FakePayPalServer fake = new FakePayPalServer();

    @DynamicPropertySource
    static void paypal(DynamicPropertyRegistry r) {
        r.add("leash.paypal.base-url", fake::baseUrl);
        r.add("leash.paypal.client-id", () -> "id");
        r.add("leash.paypal.client-secret", () -> "secret");
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

    String tug(long amount, String description) {
        var body = PurchaseFlowTest.grocery(amount);
        body.put("description", description);
        var j = http.post("/api/purchases", Http.json(body)).json();
        assertThat(j.get("outcome").asString()).isEqualTo("TUG");
        return j.get("tugId").asString();
    }

    @Test
    void listsPendingTugsWithTheirPurchase() {
        tug(3_200, "Big shop");
        var list = http.get("/api/tugs").json();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("tug").get("status").asString()).isEqualTo("PENDING");
        assertThat(list.get(0).get("entry").get("request").get("amountMinor").asLong()).isEqualTo(3_200);
        assertThat(http.get("/api/tugs?status=bogus").status()).isEqualTo(400);
    }

    @Test
    void approvingATugCreatesTheSandboxOrder() {
        String id = tug(3_200, "Big shop");
        assertThat(fake.count("POST", "/v2/checkout/orders")).isZero();

        var res = http.post("/api/tugs/" + id + "/approve", "");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().get("tug").get("status").asString()).isEqualTo("APPROVED");
        assertThat(res.json().get("entry").get("status").asString()).isEqualTo("AWAITING_PAYER");
        assertThat(res.json().get("entry").get("approveUrl").asString()).contains("sandbox.paypal.com");
        assertThat(fake.count("POST", "/v2/checkout/orders")).isEqualTo(1);
        assertThat(http.get("/api/tugs").json().size()).isZero();
    }

    @Test
    void denyingFreesTheLength() {
        String id = tug(3_200, "Big shop");
        // £32 pending: another £30 would pass the £60 length.
        var body = PurchaseFlowTest.grocery(3_000);
        body.put("description", "Second shop");
        assertThat(http.post("/api/purchases", Http.json(body)).json().get("outcome").asString()).isEqualTo("BLOCK");

        var res = http.post("/api/tugs/" + id + "/deny", "");
        assertThat(res.json().get("entry").get("status").asString()).isEqualTo("DENIED");
        body.put("description", "Third shop");
        assertThat(http.post("/api/purchases", Http.json(body)).json().get("outcome").asString()).isEqualTo("TUG");
        assertThat(fake.count("POST", "/v2/checkout/orders")).isZero();
    }

    @Test
    void aTugCanOnlyBeAnsweredOnce() {
        String id = tug(3_200, "Big shop");
        assertThat(http.post("/api/tugs/" + id + "/deny", "").status()).isEqualTo(200);
        var again = http.post("/api/tugs/" + id + "/approve", "");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body()).contains("already answered");
        assertThat(fake.count("POST", "/v2/checkout/orders")).isZero();
    }

    @Test
    void doubleClickApprovePaysOnce() throws Exception {
        String id = tug(3_200, "Big shop");
        var pool = Executors.newFixedThreadPool(5);
        var calls = new ArrayList<Callable<Integer>>();
        for (int i = 0; i < 5; i++) calls.add(() -> http.post("/api/tugs/" + id + "/approve", "").status());
        var statuses = new ArrayList<Integer>();
        for (var f : pool.invokeAll(calls)) statuses.add(f.get());
        pool.shutdown();
        assertThat(statuses).containsOnlyOnce(200);
        assertThat(fake.count("POST", "/v2/checkout/orders")).isEqualTo(1);
    }

    @Test
    void snappedAgentsTugsCantBeApproved() {
        String id = tug(3_200, "Big shop");
        http.post("/api/leashes/agent-larder/snap", "");
        var res = http.post("/api/tugs/" + id + "/approve", "");
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.body()).contains("snapped back");
        // Still pending: the human can deny it, or re-leash and approve.
        assertThat(http.get("/api/tugs").json().size()).isEqualTo(1);
    }

    @Test
    void unknownTugIs404() {
        assertThat(http.post("/api/tugs/tug-nope/approve", "").status()).isEqualTo(404);
    }
}
