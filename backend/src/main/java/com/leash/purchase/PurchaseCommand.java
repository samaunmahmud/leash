package com.leash.purchase;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What an agent sends to ask permission to spend (REST body and MCP tool arguments).
 *
 * @param requestId optional idempotency key: sending the same one twice returns the first decision instead of
 *                  creating a second purchase
 * @param mandateId optional; defaults to the agent's mandate
 * @param pageText  optional excerpt of the page the agent is buying from, checked for prompt injection
 * @param agentId   which leash: taken from the agent key when one is sent (and must match it if both are given)
 */
public record PurchaseCommand(
        @Size(max = 64) String agentId,
        @Size(max = 64) String mandateId,
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,80}", message = "may only use letters, digits and . _ : -") String requestId,
        @NotBlank @Size(max = 120) String merchant,
        @NotBlank @Size(max = 80) String category,
        @NotBlank @Size(max = 500) String description,
        long amountMinor,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO currency") String currency,
        @Size(max = 500) String task,
        @Size(max = 500) String reason,
        @Size(max = 500) String sourceUrl,
        @Size(max = 20_000) String pageText) {

    public PurchaseCommand withAgent(String id) {
        return new PurchaseCommand(id, mandateId, requestId, merchant, category, description, amountMinor, currency, task,
                reason, sourceUrl, pageText);
    }
}
