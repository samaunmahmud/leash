package com.leash.policy;

/** Every reason Leash can give, with the outcome it forces. Stable codes: the UI and ledger key off them. */
public enum RuleCode {
    // Hard rules: always BLOCK
    AGENT_REVOKED(Outcome.BLOCK),
    MANDATE_INACTIVE(Outcome.BLOCK),
    WRONG_MANDATE(Outcome.BLOCK),
    CURRENCY_MISMATCH(Outcome.BLOCK),
    INVALID_AMOUNT(Outcome.BLOCK),
    MERCHANT_BLOCKED(Outcome.BLOCK),
    MERCHANT_NOT_ALLOWED(Outcome.BLOCK),
    CATEGORY_BLOCKED(Outcome.BLOCK),
    CATEGORY_NOT_ALLOWED(Outcome.BLOCK),
    OVER_PURCHASE_MAX(Outcome.BLOCK),
    OVER_LENGTH(Outcome.BLOCK),
    VELOCITY_LIMIT(Outcome.BLOCK),

    // Soft rules: ask the human
    OVER_SLACK(Outcome.TUG),
    QUIET_HOURS(Outcome.TUG),

    // Raised by risk checkers (escalation only)
    PROMPT_INJECTION(Outcome.BLOCK),
    LOOKALIKE_MERCHANT(Outcome.BLOCK),
    TASK_MISMATCH(Outcome.TUG),
    PRICE_SPIKE(Outcome.TUG),
    DUPLICATE_REQUEST(Outcome.TUG),
    AI_RISK_HIGH(Outcome.BLOCK),
    AI_RISK_MEDIUM(Outcome.TUG);

    private final Outcome forces;

    RuleCode(Outcome forces) {
        this.forces = forces;
    }

    public Outcome forces() {
        return forces;
    }
}
