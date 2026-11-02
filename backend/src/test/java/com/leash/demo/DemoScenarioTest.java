package com.leash.demo;

import com.leash.policy.Outcome;
import com.leash.policy.PolicyEngine;
import com.leash.policy.Reason;
import com.leash.policy.RuleCode;
import com.leash.policy.Money;
import com.leash.risk.HeuristicRiskChecker;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The video depends on exactly this sequence: approve, tug, block (with the injection flagged). */
class DemoScenarioTest {

    @Test
    void scenarioPlaysApproveTugBlock() {
        var entries = new DemoScenario(new PolicyEngine(ZoneId.of("Europe/London")), new HeuristicRiskChecker()).run();
        assertThat(entries).extracting(e -> e.decision().outcome())
                .containsExactly(Outcome.APPROVE, Outcome.TUG, Outcome.BLOCK);

        List<RuleCode> blocked = entries.get(2).decision().reasons().stream().map(Reason::code).toList();
        assertThat(blocked).contains(RuleCode.PROMPT_INJECTION, RuleCode.CATEGORY_BLOCKED);
    }

    @Test
    void moneyFormatsForPeopleAndForPayPal() {
        assertThat(Money.format(1_840, "GBP")).isEqualTo("£18.40");
        assertThat(Money.toPayPalValue(1_840, "GBP")).isEqualTo("18.40");
        assertThat(Money.toPayPalValue(500, "JPY")).isEqualTo("500");
    }
}
