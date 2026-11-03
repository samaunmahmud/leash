package com.leash.api;

import com.leash.MutableClock;
import com.leash.agent.AgentKeys;
import com.leash.ledger.LedgerRepository;
import com.leash.mandate.DemoLeashes;
import com.leash.mandate.MandateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** REST purchases with agent-key auth on (the production default). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "leash.agent-auth.rest=true")
class AgentKeyAuthTest {

    @Value("${local.server.port}") int port;
    @Autowired AgentKeys keys;
    @Autowired LedgerRepository ledger;
    @Autowired MandateService leashes;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() {
        ledger.deleteAll();
        DemoLeashes.all().forEach(leashes::seed);
        clock.reset();
    }

    HttpResponse<String> buy(String key, String agentId) throws Exception {
        String body = "{" + (agentId == null ? "" : "\"agentId\":\"" + agentId + "\",")
                + "\"merchant\":\"Larder\",\"category\":\"groceries\",\"description\":\"Milk\",\"amountMinor\":500,\"currency\":\"GBP\"}";
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/purchases"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) req.header("Authorization", "Bearer " + key);
        return HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void noKeyIs401() throws Exception {
        var res = buy(null, DemoLeashes.GROCERY_AGENT);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).startsWith("Bearer"));
        assertThat(ledger.count()).isZero();
    }

    @Test
    void theKeyPicksTheAgentWhenTheBodyDoesnt() throws Exception {
        var res = buy(keys.issue(DemoLeashes.GROCERY_AGENT).key(), null);
        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(ledger.list(DemoLeashes.GROCERY_AGENT, 5)).hasSize(1);
    }

    @Test
    void anotherAgentsIdInTheBodyIs403AndRecordsNothing() throws Exception {
        var res = buy(keys.issue(DemoLeashes.OFFICE_AGENT).key(), DemoLeashes.GROCERY_AGENT);
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("only spend under its own leash");
        assertThat(ledger.count()).isZero();
    }

    @Test
    void issuingAKeyShowsItOnceAndStoresOnlyAHash() throws Exception {
        var res = new Http(port).post("/api/leashes/" + DemoLeashes.GROCERY_AGENT + "/key", "{}");
        assertThat(res.status()).isEqualTo(201);
        String key = res.json().get("key").asString();
        assertThat(key).startsWith("lk_").hasSizeGreaterThan(40);
        assertThat(res.json().get("mcpUrl").asString()).endsWith("/mcp");

        String stored = jdbc.sql("SELECT key_hash || key_prefix FROM agent_keys WHERE agent_id = ?")
                .param(DemoLeashes.GROCERY_AGENT).query(String.class).single();
        assertThat(stored).doesNotContain(key);
        assertThat(new Http(port).get("/api/leashes").body()).doesNotContain(key).contains(key.substring(0, 10));
    }
}
