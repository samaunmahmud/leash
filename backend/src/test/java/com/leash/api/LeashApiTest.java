package com.leash.api;

import com.leash.mandate.MandateService;
import com.leash.policy.Outcome;
import com.leash.policy.PolicyEngine;
import com.leash.policy.PurchaseRequest;
import com.leash.policy.RuleCode;
import com.leash.policy.SpendSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LeashApiTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    MandateService leashes;

    @Autowired
    PolicyEngine engine;

    Http http() {
        return new Http(port);
    }

    static Map<String, Object> mandate(long length, long slack) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", "Gifts");
        m.put("currency", "gbp");
        m.put("period", "MONTH");
        m.put("periodLimitMinor", length);
        m.put("autoApproveUpToMinor", slack);
        m.put("perPurchaseMaxMinor", 3_000);
        m.put("allowedCategories", List.of("Gifts"));
        m.put("blockedCategories", List.of("gift cards"));
        m.put("maxPurchasesPerHour", 3);
        m.put("sourceText", "Birthday gifts, £30 per person, never gift cards");
        return m;
    }

    @Test
    void seedsTheThreeDemoLeashes() {
        var res = http().get("/api/leashes");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().findValuesAsString("name"))
                .contains("Larder bot", "Office bot", "Travel bot", "Weekly groceries", "Office supplies", "Edinburgh trip");
        assertThat(res.json().get(0).get("mandate").get("periodLimitMinor").asLong()).isEqualTo(6_000);
    }

    @Test
    void createsALeashAndNormalisesIt() {
        var res = http().post("/api/leashes", Http.json(Map.of("agentName", "Gift bot", "kind", "gifts", "mandate", mandate(10_000, 2_000))));
        assertThat(res.status()).isEqualTo(201);
        var leash = res.json();
        String id = leash.get("agent").get("id").asString();
        assertThat(id).startsWith("agent-");
        assertThat(leash.get("agent").get("kind").asString()).isEqualTo("other");
        assertThat(leash.get("mandate").get("currency").asString()).isEqualTo("GBP");
        assertThat(leash.get("mandate").get("allowedCategories").get(0).asString()).isEqualTo("gifts");
        assertThat(leash.get("sourceText").asString()).contains("Birthday gifts");

        assertThat(http().get("/api/leashes/" + id).status()).isEqualTo(200);
        assertThat(http().get("/api/leashes").json().size()).isEqualTo(4);
    }

    @Test
    void rejectsSlackAboveLengthAndBadInput() {
        var res = http().post("/api/leashes", Http.json(Map.of("agentName", "Gift bot", "mandate", mandate(1_000, 2_000))));
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body()).contains("slack");

        var noName = http().post("/api/leashes", Http.json(Map.of("agentName", "", "mandate", mandate(1_000, 500))));
        assertThat(noName.status()).isEqualTo(400);

        var badTime = mandate(1_000, 500);
        badTime.put("quietStart", "25:00");
        badTime.put("quietEnd", "06:00");
        assertThat(http().post("/api/leashes", Http.json(Map.of("agentName", "x", "mandate", badTime))).status()).isEqualTo(400);
    }

    @Test
    void updatesAMandate() {
        var m = mandate(8_000, 3_000);
        m.put("name", "Weekly groceries");
        var res = http().put("/api/leashes/agent-larder/mandate", Http.json(m));
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().get("mandate").get("autoApproveUpToMinor").asLong()).isEqualTo(3_000);
        assertThat(res.json().get("mandate").get("id").asString()).isEqualTo("m-grocery");
    }

    @Test
    void unknownAgentIs404() {
        var res = http().post("/api/leashes/agent-nope/snap", "");
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.body()).contains("agent-nope");
    }

    @Test
    void snappedAgentIsBlockedByTheEngineAndCanBeReleashed() {
        var res = http().post("/api/leashes/agent-office/snap", "");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().get("agent").get("revoked").asBoolean()).isTrue();

        var leash = leashes.get("agent-office");
        var req = new PurchaseRequest("r", "agent-office", "m-office", "Viking", "stationery", "Pens", 1_200, "GBP",
                "restock", "low on pens", null, Instant.parse("2026-11-09T12:00:00Z"));
        var d = engine.evaluate(leash.mandate(), req, new SpendSnapshot(0, 0, leash.agent().revoked()));
        assertThat(d.outcome()).isEqualTo(Outcome.BLOCK);
        assertThat(d.reasons()).extracting(r -> r.code()).containsExactly(RuleCode.AGENT_REVOKED);

        http().post("/api/leashes/agent-office/releash", "");
        var back = leashes.get("agent-office");
        assertThat(back.agent().revoked()).isFalse();
        assertThat(engine.evaluate(back.mandate(), req, new SpendSnapshot(0, 0, back.agent().revoked())).outcome())
                .isEqualTo(Outcome.APPROVE);
    }
}
