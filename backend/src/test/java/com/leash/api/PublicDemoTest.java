package com.leash.api;

import com.leash.MutableClock;
import com.leash.auth.OwnerGate;
import com.leash.auth.RateLimiter;
import com.leash.demo.DemoAgent;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T17: the hosted public demo. Demo leashes are open to anyone; keys, PayPal and leash edits need the owner. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicDemoTest {

    static final FakePayPalServer fake = new FakePayPalServer();
    static final String PASS = "correct horse battery staple";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("leash.paypal.base-url", fake::baseUrl);
        r.add("leash.paypal.client-id", () -> "id");
        r.add("leash.paypal.client-secret", () -> "secret");
        r.add("leash.demo.public-mode", () -> "true");
        r.add("leash.demo.owner-passphrase", () -> PASS);
        r.add("leash.demo.writes-per-minute", () -> "1000");
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
    @Autowired RateLimiter limits;
    @Autowired DemoAgent agent;

    Http http;

    @BeforeEach
    void reset() {
        repo.deleteAll();
        DemoLeashes.all().forEach(leashes::seed);
        fake.reset();
        clock.reset();
        limits.clear();
        http = new Http(port);
    }

    static Map<String, String> owner() {
        return Map.of(OwnerGate.HEADER, PASS);
    }

    String ownerLeash() {
        var body = Map.of("agentName", "Gift bot", "kind", "gifts", "mandate", LeashApiTest.mandate(10_000, 1_000));
        var res = http.post("/api/leashes", Http.json(body), owner());
        assertThat(res.status()).isEqualTo(201);
        return res.json().get("agent").get("id").asString();
    }

    String tugFor(String agentId) {
        var body = PurchaseFlowTest.grocery(2_000);
        body.put("agentId", agentId);
        body.put("category", "Gifts");
        var j = http.post("/api/purchases", Http.json(body)).json();
        assertThat(j.get("outcome").asString()).isEqualTo("TUG");
        return j.get("tugId").asString();
    }

    @Test
    void healthTellsTheDashboardItIsThePublicDemo() {
        var h = http.get("/api/health").json();
        assertThat(h.get("publicDemo").asBoolean()).isTrue();
        assertThat(h.get("ownerGated").asBoolean()).isTrue();
        assertThat(h.get("ownerEnabled").asBoolean()).isTrue();
        assertThat(h.get("resetMinutes").asLong()).isEqualTo(60);
    }

    @Test
    void ownerOnlyActionsNeedThePassphrase() {
        String body = Http.json(LeashApiTest.mandate(10_000, 1_000));
        var noPass = http.post("/api/leashes/agent-larder/key", "");
        assertThat(noPass.status()).isEqualTo(401);
        assertThat(noPass.json().get("code").asString()).isEqualTo("OWNER_REQUIRED");
        assertThat(http.post("/api/leashes/agent-larder/key", "", Map.of(OwnerGate.HEADER, "nope")).status()).isEqualTo(401);
        assertThat(http.put("/api/leashes/agent-larder/mandate", body).status()).isEqualTo(401);
        assertThat(http.post("/api/wallet/setup", "").status()).isEqualTo(401);
        assertThat(http.delete("/api/wallet").status()).isEqualTo(401);
        assertThat(http.post("/api/mandates/parse", "{\"text\":\"£20 a week\"}").status()).isEqualTo(401);
        assertThat(http.post("/api/leashes", "{}").status()).isEqualTo(401);

        assertThat(http.post("/api/leashes/agent-larder/key", "", owner()).status()).isEqualTo(201);
    }

    @Test
    void pathTricksDontSkipTheGate() {
        assertThat(http.post("/api/wallet;x=1/setup", "").status()).isIn(400, 401, 404);
        assertThat(http.post("/api/leashes/agent-larder/key;a=b", "").status()).isIn(400, 401, 404);
        assertThat(http.post("/api/leashes/agent-larder/key/", "").status()).isIn(401, 404);
    }

    @Test
    void anyoneCanPlayWithTheDemoLeashes() {
        assertThat(http.post("/api/leashes/agent-office/snap", "").status()).isEqualTo(200);
        assertThat(http.post("/api/leashes/agent-office/releash", "").status()).isEqualTo(200);

        var body = PurchaseFlowTest.grocery(3_200);
        String tug = http.post("/api/purchases", Http.json(body)).json().get("tugId").asString();
        assertThat(http.post("/api/tugs/" + tug + "/deny", "").status()).isEqualTo(200);
        assertThat(http.post("/api/demo/reset", "").status()).isEqualTo(204);
        assertThat(http.post("/api/tugs/nope/approve", "").status()).isEqualTo(404);
    }

    @Test
    void theOwnersOwnLeashesNeedThePassphrase() {
        String gift = ownerLeash();
        assertThat(http.post("/api/leashes/" + gift + "/snap", "").status()).isEqualTo(401);
        String tug = tugFor(gift);
        assertThat(http.post("/api/tugs/" + tug + "/approve", "").status()).isEqualTo(401);
        assertThat(http.post("/api/tugs/" + tug + "/deny", "", owner()).status()).isEqualTo(200);
        assertThat(http.post("/api/ledger/" + tug.substring(4) + "/refund", "").status()).isEqualTo(401);
        assertThat(http.post("/api/leashes/" + gift + "/snap", "", owner()).status()).isEqualTo(200);
    }

    @Test
    void demoResetLeavesTheOwnersHistoryAlone() {
        String gift = ownerLeash();
        tugFor(gift);
        http.post("/api/purchases", Http.json(PurchaseFlowTest.grocery(1_000)));
        agent.reset();
        assertThat(repo.list(gift, 50)).hasSize(1);
        // Only the seeded examples are left on the demo leash: the real purchase went.
        assertThat(repo.list(DemoLeashes.GROCERY_AGENT, 50)).allSatisfy(e -> assertThat(e.id()).startsWith("seed-"));
    }

    @Test
    void resetSeedsLabelledHistorySoTheSuggestionShows() {
        agent.reset();
        var seeded = repo.list(DemoLeashes.GROCERY_AGENT, 50);
        assertThat(seeded).hasSize(3).allSatisfy(e -> {
            assertThat(e.request().description()).contains("seeded example");
            assertThat(e.statusDetail()).contains("Seeded example");
            assertThat(e.paypalOrderId()).isNull();
            assertThat(e.paypalCaptureId()).isNull();
            assertThat(e.decision().outcome().name()).isEqualTo("TUG");
        });
        var s = http.get("/api/leashes/agent-larder/suggestion");
        assertThat(s.status()).isEqualTo(200);
        assertThat(s.body()).contains("3000");
        // Past weeks only: this week's length is untouched.
        var card = http.get("/api/leashes/agent-larder").json();
        assertThat(card.get("usage").toString()).doesNotContain("2650");
    }

    @Test
    void passphraseGuessesAreLimited() {
        int last = 0;
        for (int i = 0; i < 12; i++) last = http.post("/api/owner/check", "", Map.of(OwnerGate.HEADER, "guess" + i)).status();
        assertThat(last).isEqualTo(429);
        limits.clear();
        assertThat(http.post("/api/owner/check", "", owner()).status()).isEqualTo(204);
    }
}
