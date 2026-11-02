package com.leash.policy;

/**
 * What happens to a purchase request. Ordered by severity: a decision's outcome is the most severe of
 * every rule that fired, and AI risk checks can only move it to the right.
 */
public enum Outcome {
    /** Within the leash: pay through PayPal without asking. */
    APPROVE,
    /** Needs a human: send a tug and wait. */
    TUG,
    /** Refused. Nothing is paid. */
    BLOCK;

    public boolean atLeast(Outcome other) {
        return this.ordinal() >= other.ordinal();
    }

    public static Outcome worst(Outcome a, Outcome b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
