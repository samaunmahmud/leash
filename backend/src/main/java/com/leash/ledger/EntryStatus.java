package com.leash.ledger;

import java.util.EnumSet;
import java.util.Set;

/** Where the money is for one ledger entry. The outcome says what Leash decided; the status says what happened next. */
public enum EntryStatus {
    /** Refused. Nothing paid. */
    BLOCKED,
    /** Waiting for the human to answer a tug. */
    TUG_PENDING,
    /** The human said no to the tug. */
    DENIED,
    /** Approved; Leash is talking to PayPal right now. */
    PROCESSING,
    /** PayPal order created; the payer still has to approve it (no saved wallet yet, see T11). */
    AWAITING_PAYER,
    /** Captured in PayPal. */
    PAID,
    /** Approved, but PayPal isn't configured, so no order was made. */
    PAYPAL_OFF,
    /** PayPal refused or failed. */
    PAYMENT_FAILED,
    /** The payer cancelled in PayPal's checkout. */
    CANCELLED,
    /** Paid, then refunded ("regret"). */
    REFUNDED;

    /**
     * What counts against the length: money that's gone or might go. Pending tugs count too, so an agent can't queue
     * several tugs to get past its limit.
     */
    public static final Set<EntryStatus> COUNTS_TOWARD_SPEND =
            EnumSet.of(TUG_PENDING, PROCESSING, AWAITING_PAYER, PAID, PAYPAL_OFF);

    public boolean countsTowardSpend() {
        return COUNTS_TOWARD_SPEND.contains(this);
    }
}
