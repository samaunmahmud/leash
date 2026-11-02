package com.leash.purchase;

import com.leash.ledger.EntryStatus;
import com.leash.ledger.LedgerEntry;
import com.leash.ledger.LedgerRepository;
import com.leash.ledger.LedgerService;
import com.leash.mandate.MandateService;
import com.leash.policy.Decision;
import com.leash.policy.Outcome;
import com.leash.policy.PolicyEngine;
import com.leash.policy.PurchaseRequest;
import com.leash.policy.SpendSnapshot;
import com.leash.risk.RiskChecker;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The core loop: an agent asks, Leash decides, the ledger records, PayPal pays (or a tug waits, or nothing happens).
 *
 * <p>Order matters and is the product's promise:
 * <ol>
 *   <li>The deterministic engine decides from the mandate and the ledger.</li>
 *   <li>Risk checks may only escalate that decision (APPROVE → TUG → BLOCK), never relax it.</li>
 *   <li>Exactly one ledger entry is written per request, blocked ones included.</li>
 *   <li>Only then is money touched, and only for APPROVE.</li>
 * </ol>
 */
@Service
public class PurchaseService {

    private final MandateService leashes;
    private final PolicyEngine engine;
    private final RiskChecker risk;
    private final LedgerService ledger;
    private final LedgerRepository repo;
    private final PaymentService payments;
    private final PageSource pages;

    /**
     * One lock per agent: two requests from the same agent are decided one after the other, so they can't both see
     * the same remaining length and both get approved. (Single instance; several instances would need a DB lock.)
     */
    private final ConcurrentHashMap<String, Object> agentLocks = new ConcurrentHashMap<>();

    public PurchaseService(MandateService leashes, PolicyEngine engine, RiskChecker risk, LedgerService ledger,
                           LedgerRepository repo, PaymentService payments, PageSource pages) {
        this.leashes = leashes;
        this.engine = engine;
        this.risk = risk;
        this.ledger = ledger;
        this.repo = repo;
        this.payments = payments;
        this.pages = pages;
    }

    public PurchaseResult request(PurchaseCommand cmd) {
        var leash = leashes.get(cmd.agentId());
        String requestId = cmd.requestId() == null || cmd.requestId().isBlank()
                ? "req-" + UUID.randomUUID().toString().substring(0, 12)
                : cmd.requestId();

        LedgerEntry entry;
        synchronized (agentLocks.computeIfAbsent(cmd.agentId(), k -> new Object())) {
            var existing = repo.find(requestId);
            if (existing.isPresent()) {
                if (!existing.get().agentId().equals(cmd.agentId())) {
                    throw new IllegalArgumentException("requestId " + requestId + " is already used by another agent");
                }
                return PurchaseResult.of(existing.get(), true);
            }

            Instant now = ledger.now();
            var r = new PurchaseRequest(requestId, cmd.agentId(),
                    cmd.mandateId() == null || cmd.mandateId().isBlank() ? leash.mandate().id() : cmd.mandateId(),
                    cmd.merchant().trim(), cmd.category().trim().toLowerCase(Locale.ROOT), cmd.description().trim(),
                    cmd.amountMinor(), cmd.currency().toUpperCase(Locale.ROOT), cmd.task(), cmd.reason(),
                    cmd.sourceUrl(), now);

            SpendSnapshot spend = ledger.snapshot(leash.mandate(), leash.agent().revoked(), now);
            Decision decision = engine.evaluate(leash.mandate(), r, spend);
            // A revoked agent gets nothing else looked at, not even risk checks.
            if (!leash.agent().revoked()) {
                decision = decision.escalate(risk.assess(r, pages.textFor(r.sourceUrl(), cmd.pageText())));
            }

            EntryStatus status = switch (decision.outcome()) {
                case BLOCK -> EntryStatus.BLOCKED;
                case TUG -> EntryStatus.TUG_PENDING;
                // Recorded as PROCESSING (which counts toward the length) before PayPal is called, so the lock can be
                // released while PayPal works without another request slipping past the limit.
                case APPROVE -> EntryStatus.PROCESSING;
            };
            var tug = decision.outcome() == Outcome.TUG
                    ? new LedgerRepository.Tug("tug-" + requestId, requestId, "PENDING", now, null)
                    : null;
            entry = ledger.create(
                    new LedgerEntry(requestId, r, decision, null, status, null, null, null, null, null, null, now, now), tug);
        }

        if (entry.decision().outcome() == Outcome.APPROVE) {
            entry = payments.pay(entry);
        }
        return PurchaseResult.of(ledger.get(entry.id()), false);
    }
}
