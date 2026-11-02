package com.leash.policy;

/**
 * What the agent has already used, computed by the caller from the ledger. Keeping this outside the engine keeps
 * the engine pure: same inputs, same decision, easy to test.
 *
 * @param spentInPeriodMinor approved purchases in the current period PLUS pending tugs, so an agent can't queue
 *                           several tugs to get past its length
 * @param purchasesLastHour  approved + pending requests in the last 60 minutes
 * @param agentRevoked       true after a snap back
 */
public record SpendSnapshot(long spentInPeriodMinor, int purchasesLastHour, boolean agentRevoked) {
    public static SpendSnapshot fresh() {
        return new SpendSnapshot(0, 0, false);
    }
}
