package com.leash.policy;

/** One rule that fired, with a sentence a non-technical person can read. */
public record Reason(RuleCode code, String message) {
    public Outcome forces() {
        return code.forces();
    }
}
