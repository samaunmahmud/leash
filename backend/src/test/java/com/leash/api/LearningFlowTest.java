package com.leash.api;

import com.leash.MutableClock;
import com.leash.ledger.LedgerRepository;
import com.leash.mandate.DemoLeashes;
import com.leash.mandate.MandateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T16 over real HTTP: answered tugs → slack suggestion → accept / not now → change log → walk report. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LearningFlowTest {

    @Value("${local.server.port}")
    int port;

    @Autowired LedgerRepository repo;
    @Autowired MandateService leashes;
    @Autowired MutableClock clock;

    Http http;
    int seq;

    @BeforeEach
    void reset() {
        repo.deleteAll();
        DemoLeashes.all().forEach(leashes::seed);
        clock.reset();
        http = new Http(port);
    }

    Map<String, Object> grocery(long amount) {
        var m = new LinkedHashMap<String, Object>();
        m.put("agentId", "agent-larder");
        m.put("merchant", "Larder");
        m.put("category", "Groceries");
        m.put("description", "Weekly shop " + (++seq));
        m.put("amountMinor", amount);
        m.put("currency", "GBP");
        m.put("task", "Buy this week's groceries");
        m.put("reason", "Weekly staples");
        return m;
    }

    /** One tug, answered, then a week passes (so lengths and duplicate checks don't interfere). */
    void tug(long amount, boolean approve) {
        var j = http.post("/api/purchases", Http.json(grocery(amount))).json();
        assertThat(j.get("outcome").asString()).isEqualTo("TUG");
        var res = http.post("/api/tugs/" + j.get("tugId").asString() + (approve ? "/approve" : "/deny"), "");
        assertThat(res.status()).isEqualTo(200);
        clock.advance(Duration.ofDays(7));
    }

    Http.Res suggestion() {
        return http.get("/api/leashes/agent-larder/suggestion");
    }

    long slack() {
        return http.get("/api/leashes/agent-larder").json().get("mandate").get("autoApproveUpToMinor").asLong();
    }

    @Test
    void threeApprovalsLeadToASuggestionThatOneClickApplies() {
        assertThat(suggestion().status()).isEqualTo(204);
        tug(2_650, true);
        tug(2_740, true);
        assertThat(suggestion().status()).isEqualTo(204); // two is not enough
        tug(2_900, true);

        var s = suggestion();
        assertThat(s.status()).isEqualTo(200);
        var j = s.json();
        assertThat(j.get("fromMinor").asLong()).isEqualTo(2_500);
        assertThat(j.get("toMinor").asLong()).isEqualTo(3_000);
        assertThat(j.get("approvals").asInt()).isEqualTo(3);
        assertThat(j.get("byAi").asBoolean()).isFalse(); // no model in tests: the template
        assertThat(j.get("sentence").asString()).contains("£26.50", "£29", "£30?");

        // A number the advisor didn't suggest is refused, not applied.
        assertThat(http.post("/api/leashes/agent-larder/suggestion/accept", "{\"toMinor\":3500}").status()).isEqualTo(409);
        assertThat(slack()).isEqualTo(2_500);

        var accepted = http.post("/api/leashes/agent-larder/suggestion/accept", "{\"toMinor\":3000}");
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.json().get("kind").asString()).isEqualTo("SLACK_RAISED");
        assertThat(accepted.json().get("summary").asString()).contains("from £25 to £30");
        assertThat(accepted.json().get("evidence").asString()).contains("3 approved tugs", "£26.50", "£29");
        assertThat(slack()).isEqualTo(3_000);

        // Recorded, and the evidence is used up: nothing more to suggest.
        var changes = http.get("/api/leash-changes").json();
        assertThat(changes.size()).isEqualTo(1);
        assertThat(changes.get(0).get("toMinor").asLong()).isEqualTo(3_000);
        assertThat(suggestion().status()).isEqualTo(204);
        assertThat(http.post("/api/leashes/agent-larder/suggestion/accept", "{\"toMinor\":3000}").status()).isEqualTo(409);

        // £28 now goes through on its own.
        assertThat(http.post("/api/purchases", Http.json(grocery(2_800))).json().get("outcome").asString()).isEqualTo("APPROVE");

        var report = http.get("/api/walk-reports/agent-larder").json();
        assertThat(report.get("changes").size()).isEqualTo(1);
        assertThat(report.get("suggestion").isNull()).isTrue();
    }

    @Test
    void aDenialInTheRangeMeansNoSuggestion() {
        tug(2_600, true);
        tug(2_700, true);
        tug(2_800, false);
        tug(2_900, true);
        assertThat(suggestion().status()).isEqualTo(204);
    }

    @Test
    void tugsHeldForQuietHoursAreNotEvidence() {
        for (int i = 0; i < 3; i++) {
            clock.set(MutableClock.START.plus(Duration.ofDays(7L * i)).minus(Duration.ofHours(10))); // Monday 02:00 UK
            var j = http.post("/api/purchases", Http.json(grocery(2_600 + 100L * i))).json();
            assertThat(j.get("outcome").asString()).isEqualTo("TUG");
            http.post("/api/tugs/" + j.get("tugId").asString() + "/approve", "");
        }
        clock.set(MutableClock.START.plus(Duration.ofDays(21)));
        assertThat(suggestion().status()).isEqualTo(204);
    }

    @Test
    void notNowHidesThatSuggestion() {
        tug(2_600, true);
        tug(2_700, true);
        tug(2_800, true);
        assertThat(suggestion().status()).isEqualTo(200);
        assertThat(http.post("/api/leashes/agent-larder/suggestion/dismiss", "{\"toMinor\":3000}").status()).isEqualTo(204);
        assertThat(suggestion().status()).isEqualTo(204);
        assertThat(slack()).isEqualTo(2_500);
    }

    @Test
    void badInputIsRefused() {
        assertThat(http.post("/api/leashes/agent-larder/suggestion/accept", "{\"toMinor\":0}").status()).isEqualTo(400);
        assertThat(http.post("/api/leashes/agent-nobody/suggestion/accept", "{\"toMinor\":3000}").status()).isEqualTo(404);
        assertThat(http.get("/api/walk-reports/agent-nobody").status()).isEqualTo(404);
    }

    @Test
    void walkReportCountsTheWeekAndLeadsWithTheHijack() {
        var run = http.post("/api/demo/run?paceMs=0", "");
        assertThat(run.status()).isEqualTo(200);

        var all = http.get("/api/walk-reports").json();
        assertThat(all.size()).isEqualTo(3);

        var r = http.get("/api/walk-reports/agent-larder").json();
        assertThat(r.get("agentName").asString()).isEqualTo("Larder bot");
        assertThat(r.get("requests").asInt()).isEqualTo(3);
        assertThat(r.get("tugged").asInt()).isEqualTo(1);
        assertThat(r.get("blocked").asInt()).isEqualTo(1);
        assertThat(r.get("paid").asInt()).isEqualTo(0); // PayPal is off in tests: approved, not paid
        assertThat(r.get("approved").asInt()).isEqualTo(1);
        assertThat(r.get("lengthMinor").asLong()).isEqualTo(6_000);
        assertThat(r.get("usedMinor").asLong()).isEqualTo(1_840);
        assertThat(r.get("pendingMinor").asLong()).isEqualTo(3_200);
        assertThat(Instant.parse(r.get("from").asString())).isBefore(Instant.parse(r.get("to").asString()));
        var block = r.get("notableBlocks").get(0);
        assertThat(block.get("code").asString()).isEqualTo("PROMPT_INJECTION");
        assertThat(block.get("evidence").asString()).contains("Ignore previous instructions");
        assertThat(r.get("summaryByAi").asBoolean()).isFalse();
        assertThat(r.get("summary").asString()).startsWith("Larder bot made 3 requests this week: 1 approved, 1 sent to you, 1 blocked.");

        var quiet = http.get("/api/walk-reports/agent-office").json();
        assertThat(quiet.get("requests").asInt()).isZero();
        assertThat(quiet.get("summary").asString()).isEqualTo("Office bot didn't ask to buy anything this week.");

        // Next week the report starts fresh.
        clock.advance(Duration.ofDays(7));
        assertThat(http.get("/api/walk-reports/agent-larder").json().get("requests").asInt()).isZero();
    }

    @Test
    void demoResetClearsLeashChanges() {
        tug(2_600, true);
        tug(2_700, true);
        tug(2_800, true);
        http.post("/api/leashes/agent-larder/suggestion/accept", "{\"toMinor\":3000}");
        assertThat(http.post("/api/demo/reset", "").status()).isEqualTo(204);
        assertThat(http.get("/api/leash-changes").json().size()).isZero();
        assertThat(slack()).isEqualTo(2_500);
    }
}
