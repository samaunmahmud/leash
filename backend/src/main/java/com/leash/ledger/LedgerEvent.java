package com.leash.ledger;

/**
 * Published whenever a ledger entry is created or changes (decision made, tug answered, payment captured...).
 * The live stream (T10) forwards these to the dashboard.
 */
public record LedgerEvent(String type, LedgerEntry entry) {
    public static final String CREATED = "entry.created";
    public static final String UPDATED = "entry.updated";
}
