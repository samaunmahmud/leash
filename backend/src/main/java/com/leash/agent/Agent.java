package com.leash.agent;

import java.time.Instant;

/**
 * An AI agent that spends under a leash.
 *
 * @param kind    grocery / office / travel / other: only used to pick the agent's identity colour in the UI
 * @param revoked true after a snap back: every request is blocked until the human re-leashes it
 */
public record Agent(String id, String name, String kind, boolean revoked, Instant revokedAt, Instant createdAt) {

    public Agent snapped(Instant at) {
        return new Agent(id, name, kind, true, at, createdAt);
    }

    public Agent releashed() {
        return new Agent(id, name, kind, false, null, createdAt);
    }
}
