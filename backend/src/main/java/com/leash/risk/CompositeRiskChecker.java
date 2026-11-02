package com.leash.risk;

import com.leash.policy.Reason;
import com.leash.policy.RuleCode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Runs several checkers and pools their reasons (one per rule code). Because the pooled reasons only ever go through
 * Decision.escalate, adding a checker can make Leash stricter but never looser.
 */
public final class CompositeRiskChecker implements RiskChecker {

    private final List<RiskChecker> checkers;

    public CompositeRiskChecker(List<RiskChecker> checkers) {
        this.checkers = List.copyOf(checkers);
    }

    @Override
    public List<Reason> assess(RiskContext ctx) {
        return assessWithExplanation(ctx).reasons();
    }

    @Override
    public Assessment assessWithExplanation(RiskContext ctx) {
        List<Reason> all = new ArrayList<>();
        Set<RuleCode> seen = EnumSet.noneOf(RuleCode.class);
        String explanation = null;
        for (RiskChecker c : checkers) {
            Assessment a = c.assessWithExplanation(ctx);
            for (Reason r : a.reasons()) {
                if (seen.add(r.code())) all.add(r);
            }
            if (a.explanation() != null && explanation == null) explanation = a.explanation();
        }
        return new Assessment(all, explanation);
    }
}
