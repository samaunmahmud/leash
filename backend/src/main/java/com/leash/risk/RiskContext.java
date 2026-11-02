package com.leash.risk;

import com.leash.policy.Mandate;
import com.leash.policy.PurchaseRequest;

import java.time.Instant;
import java.util.List;

/**
 * Everything a risk checker may look at for one request. Built by the caller from the ledger, so risk checkers stay
 * pure and easy to test.
 *
 * @param pageText the page the agent is buying from (agent-supplied excerpt or Larder's own page), or null
 * @param history  this agent's earlier requests that weren't blocked, oldest first (last 90 days)
 */
public record RiskContext(PurchaseRequest request, String pageText, Mandate mandate, List<Past> history) {

    public RiskContext {
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** One earlier purchase, reduced to what the heuristics compare. */
    public record Past(String merchant, String category, String description, long amountMinor, Instant requestedAt) {}

    public static RiskContext of(PurchaseRequest r, String pageText) {
        return new RiskContext(r, pageText, null, List.of());
    }
}
