package com.leash.purchase;

import com.leash.ledger.EntryStatus;
import com.leash.ledger.LedgerEntry;
import com.leash.policy.Money;
import com.leash.policy.Outcome;
import com.leash.policy.Reason;

import java.util.List;

/**
 * Leash's answer to an agent. {@code message} is written for the agent to relay to its human.
 *
 * @param replayed true when this is the stored answer to a repeated requestId (nothing new happened)
 */
public record PurchaseResult(
        String requestId,
        Outcome outcome,
        EntryStatus status,
        String message,
        List<Reason> reasons,
        String tugId,
        String approveUrl,
        String paypalOrderId,
        String paypalCaptureId,
        boolean replayed) {

    public static PurchaseResult of(LedgerEntry e, boolean replayed) {
        return new PurchaseResult(e.id(), e.decision().outcome(), e.status(), message(e), e.decision().reasons(),
                e.tugId(), e.approveUrl(), e.paypalOrderId(), e.paypalCaptureId(), replayed);
    }

    static String message(LedgerEntry e) {
        var r = e.request();
        String what = Money.format(r.amountMinor(), r.currency()) + " at " + r.merchant();
        String why = e.decision().reasons().isEmpty() ? "" : " " + e.decision().reasons().getFirst().message();
        return switch (e.status()) {
            case BLOCKED -> "Blocked: " + what + "." + why;
            case TUG_PENDING -> "Waiting for your human: " + what + " needs approval." + why;
            case DENIED -> "Your human said no to " + what + ".";
            case PAID -> "Approved and paid: " + what + ".";
            case AWAITING_PAYER -> "Approved: " + what + ". The payer needs to confirm it in PayPal.";
            case PAYPAL_OFF -> "Approved: " + what + " (PayPal isn't connected, so no payment was made).";
            case PROCESSING -> "Approved: " + what + ". Paying now.";
            case PAYMENT_FAILED -> "Approved, but PayPal didn't take the payment for " + what + ".";
            case CANCELLED -> "The payer cancelled " + what + " in PayPal.";
            case REFUNDED -> "Refunded: " + what + ".";
        };
    }
}
