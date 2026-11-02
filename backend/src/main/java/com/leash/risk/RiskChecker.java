package com.leash.risk;

import com.leash.policy.Reason;

import java.util.List;

/**
 * A second opinion on a purchase. Implementations return extra reasons; Decision.escalate adds them, which can
 * only make the outcome stricter. A risk checker can never approve anything.
 *
 * <p>Implementations: {@link HeuristicRiskChecker} (no AI, always on) and LlmRiskChecker (T06), combined by
 * {@link CompositeRiskChecker}.
 */
public interface RiskChecker {

    List<Reason> assess(RiskContext ctx);

    /** Optional sentence explaining the assessment (the LLM writes one; heuristics don't). */
    default Assessment assessWithExplanation(RiskContext ctx) {
        return new Assessment(assess(ctx), null);
    }

    record Assessment(List<Reason> reasons, String explanation) {
        public Assessment {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
