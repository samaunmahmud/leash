package com.leash.policy;

import com.leash.demo.DemoScenario;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyEngineTest {

    private static final ZoneId UK = ZoneId.of("Europe/London");
    private final PolicyEngine engine = new PolicyEngine(UK);
    private final Mandate grocery = DemoScenario.groceryLeash(); // £60/week, slack £25, cap £60, quiet 00–06

    private static Instant at(int hour) {
        return ZonedDateTime.of(2026, 11, 9, hour, 0, 0, 0, UK).toInstant();
    }

    private PurchaseRequest req(String merchant, String category, long amount) {
        return new PurchaseRequest("r", grocery.agentId(), grocery.id(), merchant, category, "item", amount, "GBP",
                "groceries", "needed", null, at(12));
    }

    private Decision eval(PurchaseRequest r, long spent) {
        return engine.evaluate(grocery, r, new SpendSnapshot(spent, 0, false));
    }

    private static Set<RuleCode> codes(Decision d) {
        return d.reasons().stream().map(Reason::code).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void approvesWithinSlackAndLength() {
        Decision d = eval(req("Larder", "groceries", 1_840), 0);
        assertThat(d.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(d.reasons()).isEmpty();
    }

    @Test
    void slackBoundaryIsInclusive() {
        assertThat(eval(req("Larder", "groceries", 2_500), 0).outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(eval(req("Larder", "groceries", 2_501), 0).outcome()).isEqualTo(Outcome.TUG);
    }

    @Test
    void tugsAboveSlack() {
        Decision d = eval(req("Larder", "groceries", 3_200), 0);
        assertThat(d.outcome()).isEqualTo(Outcome.TUG);
        assertThat(codes(d)).containsExactly(RuleCode.OVER_SLACK);
    }

    @Test
    void blocksPastLengthIncludingPendingTugs() {
        Decision d = eval(req("Larder", "groceries", 1_000), 5_500);
        assertThat(d.outcome()).isEqualTo(Outcome.BLOCK);
        assertThat(codes(d)).contains(RuleCode.OVER_LENGTH);
        assertThat(d.reasons().getFirst().message()).contains("£5.00 left");
    }

    @Test
    void merchantMatchingIsCaseInsensitive() {
        assertThat(eval(req("  larder ", "Groceries", 1_000), 0).outcome()).isEqualTo(Outcome.APPROVE);
    }

    @Test
    void blocksUnlistedMerchantAndBlockedCategory() {
        Decision d = eval(req("Tesc0", "gift cards", 1_000), 0);
        assertThat(d.outcome()).isEqualTo(Outcome.BLOCK);
        assertThat(codes(d)).contains(RuleCode.MERCHANT_NOT_ALLOWED, RuleCode.CATEGORY_BLOCKED);
    }

    @Test
    void reportsEveryViolationNotJustTheFirst() {
        Decision d = eval(req("Unknown", "electronics", 90_000), 0);
        assertThat(codes(d)).contains(RuleCode.MERCHANT_NOT_ALLOWED, RuleCode.CATEGORY_NOT_ALLOWED,
                RuleCode.OVER_PURCHASE_MAX, RuleCode.OVER_LENGTH, RuleCode.OVER_SLACK);
        assertThat(d.outcome()).isEqualTo(Outcome.BLOCK);
    }

    @Test
    void revokedAgentIsBlockedBeforeAnythingElse() {
        Decision d = engine.evaluate(grocery, req("Larder", "groceries", 100), new SpendSnapshot(0, 0, true));
        assertThat(d.outcome()).isEqualTo(Outcome.BLOCK);
        assertThat(codes(d)).containsExactly(RuleCode.AGENT_REVOKED);
    }

    @Test
    void velocityLimit() {
        Decision d = engine.evaluate(grocery, req("Larder", "groceries", 100), new SpendSnapshot(0, 6, false));
        assertThat(codes(d)).contains(RuleCode.VELOCITY_LIMIT);
        assertThat(d.outcome()).isEqualTo(Outcome.BLOCK);
    }

    @Test
    void currencyMismatchBlocks() {
        var r = new PurchaseRequest("r", grocery.agentId(), grocery.id(), "Larder", "groceries", "x", 100, "USD",
                "t", "r", null, at(12));
        assertThat(codes(eval(r, 0))).containsExactly(RuleCode.CURRENCY_MISMATCH);
    }

    @Test
    void nonPositiveAmountBlocks() {
        assertThat(codes(eval(req("Larder", "groceries", 0), 0))).containsExactly(RuleCode.INVALID_AMOUNT);
    }

    @Test
    void agentCannotUseAnotherAgentsMandate() {
        var r = new PurchaseRequest("r", "agent-other", grocery.id(), "Larder", "groceries", "x", 100, "GBP",
                "t", "r", null, at(12));
        assertThat(codes(eval(r, 0))).contains(RuleCode.WRONG_MANDATE);
    }

    @Test
    void quietHoursTugAndHandleMidnight() {
        var r = new PurchaseRequest("r", grocery.agentId(), grocery.id(), "Larder", "groceries", "x", 100, "GBP",
                "t", "r", null, at(3));
        Decision d = eval(r, 0);
        assertThat(d.outcome()).isEqualTo(Outcome.TUG);
        assertThat(codes(d)).containsExactly(RuleCode.QUIET_HOURS);

        Mandate overnight = new Mandate("m", "a", "n", "GBP", Period.DAY, 1, 1, 0, Set.of(), Set.of(), Set.of(), Set.of(),
                0, LocalTime.of(23, 0), LocalTime.of(6, 0), true);
        assertThat(overnight.isQuiet(LocalTime.of(23, 30))).isTrue();
        assertThat(overnight.isQuiet(LocalTime.of(5, 59))).isTrue();
        assertThat(overnight.isQuiet(LocalTime.of(6, 0))).isFalse();
        assertThat(overnight.isQuiet(LocalTime.of(12, 0))).isFalse();
    }
}
