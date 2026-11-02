package com.leash.purchase;

import com.leash.api.NotFoundException;
import com.leash.ledger.EntryStatus;
import com.leash.ledger.LedgerEntry;
import com.leash.ledger.LedgerRepository;
import com.leash.ledger.LedgerService;
import com.leash.paypal.PayPalClient;
import com.leash.paypal.PayPalClient.PayPalOrder;
import com.leash.paypal.PayPalException;
import com.leash.policy.Money;
import com.leash.policy.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Turns an approved ledger entry into PayPal money movement, and records what happened. Only ever called for
 * entries the engine approved or the human approved through a tug: there is no other way to reach PayPal.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PayPalClient paypal;
    private final LedgerService ledger;
    private final LedgerRepository repo;

    public PaymentService(PayPalClient paypal, LedgerService ledger, LedgerRepository repo) {
        this.paypal = paypal;
        this.ledger = ledger;
        this.repo = repo;
    }

    /**
     * Pays for an approved entry. Never throws: a PayPal failure becomes PAYMENT_FAILED on the ledger, so the agent
     * gets an answer and the human sees why.
     */
    public LedgerEntry pay(LedgerEntry e) {
        if (e.decision().outcome() == Outcome.BLOCK) {
            throw new IllegalStateException("Refusing to pay for a blocked request"); // can't happen; guard anyway
        }
        var r = e.request();
        if (!paypal.configured()) {
            return ledger.update(e.withStatus(EntryStatus.PAYPAL_OFF,
                    "Approved, but PayPal isn't configured, so no order was created.", ledger.now()));
        }
        try {
            PayPalOrder order = paypal.createOrder(e.id(), r.amountMinor(), r.currency(),
                    r.merchant() + ": " + r.description());
            if (order.completed()) {
                return ledger.update(e.withPayPal(order.id(), order.captureId(), null, ledger.now())
                        .withStatus(EntryStatus.PAID, "Paid via PayPal sandbox.", ledger.now()));
            }
            return ledger.update(e.withPayPal(order.id(), null, order.approveUrl().orElse(null), ledger.now())
                    .withStatus(EntryStatus.AWAITING_PAYER,
                            "PayPal order created. Waiting for the payer to approve " + Money.format(r.amountMinor(), r.currency()) + ".",
                            ledger.now()));
        } catch (PayPalException ex) {
            log.warn("Payment for {} failed: {}", e.id(), ex.getMessage());
            return ledger.update(e.withStatus(EntryStatus.PAYMENT_FAILED, ex.getMessage(), ledger.now()));
        }
    }

    /** Captures an order the payer approved. Safe to call twice: a second call on a PAID entry is a no-op. */
    public LedgerEntry capture(String entryId) {
        LedgerEntry e = ledger.get(entryId);
        if (e.status() == EntryStatus.PAID) return e;
        if (e.status() != EntryStatus.AWAITING_PAYER || e.paypalOrderId() == null) {
            throw new IllegalStateException("Entry " + entryId + " isn't waiting for a PayPal capture (it's " + e.status() + ").");
        }
        try {
            PayPalOrder order = paypal.captureOrder(e.paypalOrderId());
            String detail = "COMPLETED".equals(order.captureStatus())
                    ? "Paid via PayPal sandbox."
                    : "Captured; PayPal reports the capture as " + order.captureStatus() + ".";
            return ledger.update(e.withPayPal(order.id(), order.captureId(), null, ledger.now())
                    .withStatus(EntryStatus.PAID, detail, ledger.now()));
        } catch (PayPalException ex) {
            if ("ORDER_NOT_APPROVED".equals(ex.errorName())) {
                // Not the payer's final answer yet: leave it waiting.
                throw new IllegalStateException("The payer hasn't approved this order in PayPal yet.");
            }
            log.warn("Capture for {} failed: {}", entryId, ex.getMessage());
            return ledger.update(e.withStatus(EntryStatus.PAYMENT_FAILED, ex.getMessage(), ledger.now()));
        }
    }

    /** PayPal sent the payer back: capture the matching entry, if there is one. */
    public Optional<LedgerEntry> captureByOrder(String orderId) {
        return repo.findByOrderId(orderId).map(e -> capture(e.id()));
    }

    public Optional<LedgerEntry> cancelByOrder(String orderId) {
        return repo.findByOrderId(orderId)
                .filter(e -> e.status() == EntryStatus.AWAITING_PAYER)
                .map(e -> ledger.update(e.withStatus(EntryStatus.CANCELLED, "The payer cancelled in PayPal.", ledger.now())));
    }

    public LedgerEntry requireEntry(String id) {
        return repo.find(id).orElseThrow(() -> new NotFoundException("No ledger entry " + id));
    }
}
