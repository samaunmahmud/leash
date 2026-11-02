package com.leash.purchase;

import com.leash.api.NotFoundException;
import com.leash.ledger.EntryStatus;
import com.leash.ledger.LedgerEntry;
import com.leash.ledger.LedgerRepository;
import com.leash.ledger.LedgerRepository.Tug;
import com.leash.ledger.LedgerService;
import com.leash.mandate.MandateService;
import org.springframework.stereotype.Service;

import java.util.List;

/** The human side of a tug: see what's waiting, then approve (pay) or deny. */
@Service
public class TugService {

    /** A tug with the purchase it's about. */
    public record TugView(Tug tug, LedgerEntry entry) {}

    private final LedgerRepository repo;
    private final LedgerService ledger;
    private final PaymentService payments;
    private final MandateService leashes;

    public TugService(LedgerRepository repo, LedgerService ledger, PaymentService payments, MandateService leashes) {
        this.repo = repo;
        this.ledger = ledger;
        this.payments = payments;
        this.leashes = leashes;
    }

    public List<TugView> list(String status) {
        return repo.tugs(status).stream().map(t -> new TugView(t, ledger.get(t.entryId()))).toList();
    }

    /**
     * Approves and pays. The tug's PENDING → APPROVED switch is a single conditional UPDATE, so a double click or
     * two open tabs can't both pay. A snapped-back agent can't be paid through an old tug: re-leash it first.
     */
    public TugView approve(String tugId) {
        Tug tug = require(tugId);
        LedgerEntry entry = ledger.get(tug.entryId());
        if (leashes.get(entry.agentId()).agent().revoked()) {
            throw new IllegalStateException("This agent was snapped back. Re-leash it before approving its tugs.");
        }
        if (!repo.resolveTug(tugId, "APPROVED", ledger.now())) {
            throw new IllegalStateException("This tug was already answered (" + require(tugId).status() + ").");
        }
        LedgerEntry processing = ledger.update(entry.withStatus(EntryStatus.PROCESSING, "Approved by you.", ledger.now()));
        payments.pay(processing);
        return new TugView(require(tugId), ledger.get(entry.id()));
    }

    /** Denies. The pending amount stops counting toward the length straight away. */
    public TugView deny(String tugId) {
        Tug tug = require(tugId);
        if (!repo.resolveTug(tugId, "DENIED", ledger.now())) {
            throw new IllegalStateException("This tug was already answered (" + require(tugId).status() + ").");
        }
        LedgerEntry entry = ledger.get(tug.entryId());
        ledger.update(entry.withStatus(EntryStatus.DENIED, "You said no.", ledger.now()));
        return new TugView(require(tugId), ledger.get(entry.id()));
    }

    private Tug require(String id) {
        return repo.findTug(id).orElseThrow(() -> new NotFoundException("No tug " + id));
    }
}
