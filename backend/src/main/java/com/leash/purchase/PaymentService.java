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
import com.leash.vault.WalletService;
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
    private final WalletService wallet;

    public PaymentService(PayPalClient paypal, LedgerService ledger, LedgerRepository repo, WalletService wallet) {
        this.paypal = paypal;
        this.ledger = ledger;
        this.repo = repo;
        this.wallet = wallet;
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
        String description = r.merchant() + ": " + r.description();
        String fallbackNote = "";
        var vaultId = wallet.activeVaultId();
        if (vaultId.isPresent()) {
            try {
                PayPalOrder order = paypal.createVaultOrder(e.id(), vaultId.get(), r.amountMinor(), r.currency(), description);
                if (!order.completed() && "APPROVED".equals(order.status())) order = paypal.captureOrder(order.id());
                if (order.completed() && order.captureId() != null) {
                    return ledger.update(e.withPayPal(order.id(), order.captureId(), null, ledger.now())
                            .withStatus(EntryStatus.PAID, "Paid via PayPal sandbox with your saved PayPal (no popup).", ledger.now()));
                }
                fallbackNote = "Your saved PayPal returned " + order.status() + ", so this one needs your approval. ";
                log.warn("Vault order {} for {} came back {}; falling back to an approve link", order.id(), e.id(), order.status());
            } catch (PayPalException ex) {
                // A declined or refused saved wallet must not lose the purchase: ask the human instead.
                fallbackNote = "Your saved PayPal couldn't be charged (" + ex.getMessage() + "), so this one needs your approval. ";
                log.warn("Vault payment for {} failed, falling back to an approve link: {}", e.id(), ex.getMessage());
            }
        }
        try {
            PayPalOrder order = paypal.createOrder(e.id(), r.amountMinor(), r.currency(), description);
            if (order.completed()) {
                return ledger.update(e.withPayPal(order.id(), order.captureId(), null, ledger.now())
                        .withStatus(EntryStatus.PAID, "Paid via PayPal sandbox.", ledger.now()));
            }
            return ledger.update(e.withPayPal(order.id(), null, order.approveUrl().orElse(null), ledger.now())
                    .withStatus(EntryStatus.AWAITING_PAYER,
                            fallbackNote + "PayPal order created. Waiting for the payer to approve " + Money.format(r.amountMinor(), r.currency()) + ".",
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

    /**
     * "Regret": refunds a paid purchase in full through PayPal. Only the human can do this (dashboard), never an agent.
     * Safe to repeat: the PayPal-Request-Id is fixed per entry, so PayPal hands back the same refund rather than a
     * second one, and an entry already REFUNDED is returned as is. A refund frees the length, because REFUNDED doesn't
     * count toward spend.
     */
    public LedgerEntry refund(String entryId) {
        LedgerEntry e = ledger.get(entryId);
        if (e.status() == EntryStatus.REFUNDED) return e;
        if (e.status() != EntryStatus.PAID || e.paypalCaptureId() == null) {
            throw new IllegalStateException("Only a purchase paid through PayPal can be refunded (this one is " + e.status() + ").");
        }
        PayPalClient.Refund refund = paypal.refundCapture(e.paypalCaptureId(), "refund-" + e.id());
        if (refund.failed()) {
            throw new IllegalStateException("PayPal reported the refund as " + refund.status() + ". Nothing was refunded.");
        }
        String amount = Money.format(e.request().amountMinor(), e.request().currency());
        LedgerEntry next = refund.completed()
                ? e.withRefund(refund.id(), ledger.now()).withStatus(EntryStatus.REFUNDED,
                        "Refunded " + amount + " via PayPal sandbox. The length is freed.", ledger.now())
                : e.withRefund(refund.id(), ledger.now()).withStatus(EntryStatus.PAID,
                        "Refund of " + amount + " requested; PayPal reports it as " + refund.status() + ".", ledger.now());
        // If a webhook got there first, keep what it wrote.
        return ledger.updateIf(next, EntryStatus.PAID).orElseGet(() -> ledger.get(entryId));
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
