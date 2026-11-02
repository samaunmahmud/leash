package com.leash.api;

import com.leash.MutableClock;
import com.leash.paypal.FakePayPalServer;
import com.leash.purchase.PageSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** T09: the full scenario plays through the real flow: £18.40 approve, £32 tug, injection block. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoRunTest {

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

    @Autowired MutableClock clock;
    @Autowired PageSource pages;

    Http http;

    @BeforeEach
    void setUp() {
        clock.reset();
        fake.reset();
        http = new Http(port);
    }

    @Test
    void playsApproveTugBlock() {
        var res = http.post("/api/demo/run?paceMs=0", "");
        assertThat(res.status()).isEqualTo(200);
        var results = res.json().get("results");
        assertThat(results.size()).isEqualTo(3);

        assertThat(results.get(0).get("outcome").asString()).isEqualTo("APPROVE");
        assertThat(results.get(0).get("status").asString()).isEqualTo("AWAITING_PAYER");
        assertThat(results.get(0).get("message").asString()).contains("£18.40");

        assertThat(results.get(1).get("outcome").asString()).isEqualTo("TUG");
        assertThat(results.get(1).get("message").asString()).contains("£32.00");

        assertThat(results.get(2).get("outcome").asString()).isEqualTo("BLOCK");
        assertThat(results.get(2).get("reasons").findValuesAsString("code"))
                .contains("PROMPT_INJECTION", "CATEGORY_BLOCKED", "TASK_MISMATCH");

        assertThat(http.get("/api/ledger").json().size()).isEqualTo(3);
        assertThat(fake.count("POST", "/v2/checkout/orders")).isEqualTo(1); // only the approved one
    }

    @Test
    void runsAgainCleanlyAfterAReset() {
        http.post("/api/demo/run?paceMs=0", "");
        http.post("/api/leashes/agent-larder/snap", "");
        var again = http.post("/api/demo/run?paceMs=0", "").json().get("results");
        // Reset re-leashed the agent and cleared the ledger, so the duplicate check doesn't fire either.
        assertThat(again.get(0).get("outcome").asString()).isEqualTo("APPROVE");
        assertThat(http.get("/api/ledger").json().size()).isEqualTo(3);
    }

    @Test
    void asyncRunReturnsAccepted() throws Exception {
        var res = http.post("/api/demo/run?paceMs=50", "");
        assertThat(res.status()).isEqualTo(202);
        for (int i = 0; i < 100 && http.get("/api/ledger").json().size() < 3; i++) Thread.sleep(20);
        assertThat(http.get("/api/ledger").json().size()).isEqualTo(3);
    }

    @Test
    void rejectsSillyPace() {
        assertThat(http.post("/api/demo/run?paceMs=-1", "").status()).isEqualTo(400);
    }

    @Test
    void servesTheShopAndItsPoisonedPage() {
        assertThat(http.get("/larder/").status()).isEqualTo(200);
        assertThat(http.get("/larder/products.json").body()).contains("weekly-staples", "gift-bundle");
        assertThat(http.get("/larder/gift-bundle.html").body()).contains("display:none", "Ignore previous instructions");
        assertThat(http.get("/api/demo/larder").json().size()).isEqualTo(7);
    }

    @Test
    void agentThatHidesThePageIsStillCaught() {
        http.post("/api/demo/reset", "");
        // A hijacked agent sends no page text at all, just the URL. Leash reads its own Larder page anyway.
        var body = PurchaseFlowTest.grocery(1_000);
        body.put("category", "groceries");
        body.put("description", "Gift bundle");
        body.put("sourceUrl", "/larder/gift-bundle.html");
        var j = http.post("/api/purchases", Http.json(body)).json();
        assertThat(j.get("outcome").asString()).isEqualTo("BLOCK");
        assertThat(j.get("reasons").findValuesAsString("code")).contains("PROMPT_INJECTION");
    }

    @Test
    void onlyReadsLarderPagesFromThisHost() {
        assertThat(pages.textFor("/larder/gift-bundle.html", null)).contains("Ignore previous instructions");
        assertThat(pages.textFor("http://localhost:8080/larder/gift-bundle.html", null)).contains("Ignore previous");
        assertThat(pages.textFor("https://evil.example/larder/gift-bundle.html", "x")).isEqualTo("x");
        assertThat(pages.textFor("/larder/../application.yml", "x")).isEqualTo("x");
        assertThat(pages.textFor("/larder/nope.html", null)).isNull();
    }
}
