package com.leash.api;

import com.leash.MutableClock;
import com.leash.ledger.LedgerRepository;
import com.leash.mandate.DemoLeashes;
import com.leash.mandate.MandateService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole app with a model configured, pointed at a local fake OpenAI-compatible server: the composer gets an AI
 * reading, and the AI risk check's verdict reaches the decision and the ledger (only ever escalating).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiWiringTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AtomicReference<String> riskReply = new AtomicReference<>();
    static final HttpServer model = start();

    static final String MANDATE_REPLY = """
            {"name":"Weekly groceries","agentName":"Pantry bot","kind":"grocery","currency":"GBP","period":"WEEK",
             "length":"60.00","autoApproveUpTo":"25.00","perPurchaseMax":"0","allowedMerchants":[],"blockedMerchants":[],
             "allowedCategories":["groceries","household"],"blockedCategories":["gift cards"],"maxPurchasesPerHour":0,
             "quietStart":null,"quietEnd":null,"notes":[]}""";

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/v1/chat/completions", ex -> {
                var req = JSON.readTree(ex.getRequestBody().readAllBytes());
                String system = req.get("messages").get(0).get("content").asString();
                String content = system.contains("risk checker") ? riskReply.get() : MANDATE_REPLY;
                byte[] out = JSON.writeValueAsString(
                        Map.of("choices", java.util.List.of(Map.of("message", Map.of("role", "assistant", "content", content)))))
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void llm(DynamicPropertyRegistry r) {
        r.add("leash.llm.base-url", () -> "http://localhost:" + model.getAddress().getPort() + "/v1");
        r.add("leash.llm.api-key", () -> "test-key");
        r.add("leash.llm.model", () -> "test-model");
    }

    @AfterAll
    static void stop() {
        model.stop(0);
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
        clock.reset();
        http = new Http(port);
    }

    static String milk(long amount) {
        var m = new LinkedHashMap<String, Object>();
        m.put("agentId", "agent-larder");
        m.put("merchant", "Larder");
        m.put("category", "groceries");
        m.put("description", "Milk, eggs, bread");
        m.put("amountMinor", amount);
        m.put("currency", "GBP");
        m.put("task", "Buy this week's groceries");
        return Http.json(m);
    }

    @Test
    void healthSaysAiIsConfigured() {
        assertThat(http.get("/api/health").body()).contains("\"aiConfigured\":true");
    }

    @Test
    void composerGetsTheAiReading() {
        var res = http.post("/api/mandates/parse", "{\"text\":\"Groceries, £60 a week, supermarkets only, ask me over £25\"}");
        assertThat(res.status()).isEqualTo(200);
        var p = res.json();
        assertThat(p.get("source").asString()).isEqualTo("ai");
        assertThat(p.get("agentName").asString()).isEqualTo("Pantry bot");
        assertThat(p.get("mandate").get("periodLimitMinor").asLong()).isEqualTo(6_000);
    }

    @Test
    void mediumRiskTurnsAnApprovalIntoATugWithTheExplanation() {
        riskReply.set("{\"risk\":\"MEDIUM\",\"signals\":[\"unusual\"],\"explanation\":\"Check this one, it looks unusual.\"}");
        var res = http.post("/api/purchases", milk(1_840));
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body()).contains("\"outcome\":\"TUG\"", "AI_RISK_MEDIUM", "Check this one, it looks unusual.");
    }

    @Test
    void lowRiskLeavesTheEnginesDecisionAlone() {
        riskReply.set("{\"risk\":\"LOW\",\"signals\":[],\"explanation\":\"Ordinary groceries.\"}");
        var approve = http.post("/api/purchases", milk(1_840));
        assertThat(approve.body()).contains("\"outcome\":\"APPROVE\"").doesNotContain("AI_RISK");

        var tug = http.post("/api/purchases", milk(3_200)); // over the £25 slack: the engine tugs, LOW can't undo it
        assertThat(tug.body()).contains("\"outcome\":\"TUG\"", "OVER_SLACK");
    }

    @Test
    void brokenModelRepliesLeaveHeuristicsInCharge() {
        riskReply.set("this is not json");
        var res = http.post("/api/purchases", milk(1_840));
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body()).contains("\"outcome\":\"APPROVE\"");
    }
}
