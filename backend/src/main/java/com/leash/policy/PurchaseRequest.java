package com.leash.policy;

import java.time.Instant;

/**
 * What an agent asks Leash for.
 *
 * @param task      the job the human gave the agent ("buy this week's groceries"): used by risk checks
 * @param reason    the agent's own justification for this purchase
 * @param sourceUrl the page the agent found the item on, if any (checked for prompt injection)
 */
public record PurchaseRequest(
        String id,
        String agentId,
        String mandateId,
        String merchant,
        String category,
        String description,
        long amountMinor,
        String currency,
        String task,
        String reason,
        String sourceUrl,
        Instant requestedAt) {
}
