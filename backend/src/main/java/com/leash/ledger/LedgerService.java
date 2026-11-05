package com.leash.ledger;

import com.leash.api.NotFoundException;
import com.leash.config.PolicyConfig;
import com.leash.policy.Mandate;
import com.leash.policy.Periods;
import com.leash.policy.SpendSnapshot;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Reads and writes the ledger, works out what an agent has used, and announces every change. */
@Service
public class LedgerService {

    private final LedgerRepository repo;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public LedgerService(LedgerRepository repo, ApplicationEventPublisher events, Clock clock) {
        this.repo = repo;
        this.events = events;
        this.clock = clock;
    }

    /** What the engine needs to know about past spending. Computed from the ledger so it can't drift. */
    public SpendSnapshot snapshot(Mandate m, boolean revoked, Instant now) {
        Instant start = Periods.start(m.period(), now, PolicyConfig.ZONE);
        long spent = repo.spentSince(m.agentId(), m.currency(), start);
        int lastHour = repo.countSince(m.agentId(), now.minus(Duration.ofHours(1)));
        return new SpendSnapshot(spent, lastHour, revoked);
    }

    /** Spend summary for the leash cards. */
    public record Usage(long spentMinor, long pendingMinor, Instant periodStart) {}

    public Usage usage(Mandate m) {
        Instant now = clock.instant();
        Instant start = Periods.start(m.period(), now, PolicyConfig.ZONE);
        long used = repo.spentSince(m.agentId(), m.currency(), start);
        long pending = repo.since(m.agentId(), start).stream()
                .filter(e -> e.status() == EntryStatus.TUG_PENDING && e.request().currency().equals(m.currency()))
                .mapToLong(e -> e.request().amountMinor()).sum();
        return new Usage(used - pending, pending, start);
    }

    public LedgerEntry get(String id) {
        return repo.find(id).orElseThrow(() -> new NotFoundException("No ledger entry " + id));
    }

    public List<LedgerEntry> list(String agentId, int limit) {
        return repo.list(agentId, Math.max(1, Math.min(limit, 500)));
    }

    /** Writes a new entry (and its tug, for TUG outcomes) in one transaction, then announces it. */
    @Transactional
    public LedgerEntry create(LedgerEntry e, LedgerRepository.Tug tug) {
        repo.insert(e);
        if (tug != null) repo.insertTug(tug);
        LedgerEntry saved = get(e.id());
        events.publishEvent(new LedgerEvent(LedgerEvent.CREATED, saved));
        return saved;
    }

    public LedgerEntry update(LedgerEntry e) {
        repo.update(e);
        LedgerEntry saved = get(e.id());
        events.publishEvent(new LedgerEvent(LedgerEvent.UPDATED, saved));
        return saved;
    }

    /** Conditional update (see {@link LedgerRepository#updateIfStatus}); announces only when it applied. */
    public java.util.Optional<LedgerEntry> updateIf(LedgerEntry e, EntryStatus expected) {
        if (!repo.updateIfStatus(e, expected)) return java.util.Optional.empty();
        LedgerEntry saved = get(e.id());
        events.publishEvent(new LedgerEvent(LedgerEvent.UPDATED, saved));
        return java.util.Optional.of(saved);
    }

    public Instant now() {
        return clock.instant();
    }
}
