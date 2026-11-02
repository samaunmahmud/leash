package com.leash.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The core promise: risk checks (AI included) can make a decision stricter, never looser. */
class DecisionEscalationTest {

    @ParameterizedTest
    @EnumSource(RuleCode.class)
    void escalatingNeverLowersSeverity(RuleCode risk) {
        for (RuleCode base : RuleCode.values()) {
            Decision before = Decision.of("r", List.of(new Reason(base, "base")));
            Decision after = before.escalate(List.of(new Reason(risk, "risk")));
            assertThat(after.outcome().atLeast(before.outcome()))
                    .as("%s escalated by %s", base, risk)
                    .isTrue();
        }
    }

    @Test
    void emptyEscalationIsANoOp() {
        Decision d = Decision.of("r", List.of());
        assertThat(d.escalate(List.of())).isSameAs(d);
        assertThat(d.escalate(null)).isSameAs(d);
    }

    @Test
    void injectionTurnsAnApprovalIntoABlock() {
        Decision approved = Decision.of("r", List.of());
        Decision after = approved.escalate(List.of(new Reason(RuleCode.PROMPT_INJECTION, "x")));
        assertThat(after.outcome()).isEqualTo(Outcome.BLOCK);
        assertThat(after.reasons()).hasSize(1);
    }
}
