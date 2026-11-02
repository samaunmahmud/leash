package com.leash.policy;

import java.util.ArrayList;
import java.util.List;

/** The verdict on one purchase request, with every reason that contributed. */
public record Decision(String requestId, Outcome outcome, List<Reason> reasons) {

    public Decision {
        reasons = List.copyOf(reasons);
    }

    public static Decision of(String requestId, List<Reason> reasons) {
        Outcome outcome = Outcome.APPROVE;
        for (Reason r : reasons) outcome = Outcome.worst(outcome, r.forces());
        return new Decision(requestId, outcome, reasons);
    }

    /**
     * Adds risk findings. This can only make the decision stricter: the outcome after escalating is always at
     * least as severe as before. That's the guarantee that an AI can never approve money on its own.
     */
    public Decision escalate(List<Reason> riskReasons) {
        if (riskReasons == null || riskReasons.isEmpty()) return this;
        List<Reason> all = new ArrayList<>(reasons);
        all.addAll(riskReasons);
        Decision next = Decision.of(requestId, all);
        // Belt and braces: Decision.of already takes the worst, but keep the invariant explicit.
        Outcome kept = Outcome.worst(outcome, next.outcome());
        return new Decision(requestId, kept, all);
    }

    public boolean approved() {
        return outcome == Outcome.APPROVE;
    }
}
