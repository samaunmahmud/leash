-- Every purchase request an agent makes, with the decision and what happened to the money.
-- One row per request, blocked ones included: the ledger is the audit trail.

CREATE TABLE ledger_entries (
    id                 VARCHAR(80)  PRIMARY KEY,         -- the purchase request ID (agents may supply it for idempotency)
    agent_id           VARCHAR(64)  NOT NULL REFERENCES agents (id),
    mandate_id         VARCHAR(64)  NOT NULL,
    merchant           VARCHAR(120) NOT NULL,
    category           VARCHAR(80)  NOT NULL,
    description        VARCHAR(500) NOT NULL,
    amount_minor       BIGINT       NOT NULL,
    currency           CHAR(3)      NOT NULL,
    task               VARCHAR(500),
    reason             VARCHAR(500),
    source_url         VARCHAR(500),
    requested_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    outcome            VARCHAR(8)   NOT NULL,            -- APPROVE / TUG / BLOCK
    reasons_json       TEXT         NOT NULL,            -- [{code, message}] every rule and risk signal that fired
    risk_explanation   TEXT,                             -- the AI risk checker's sentence, if it ran (T06)
    status             VARCHAR(24)  NOT NULL,            -- see EntryStatus
    status_detail      VARCHAR(500),
    paypal_order_id    VARCHAR(64),
    paypal_capture_id  VARCHAR(64),
    paypal_refund_id   VARCHAR(64),
    approve_url        VARCHAR(500),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ledger_agent_time ON ledger_entries (agent_id, requested_at);
CREATE INDEX ledger_order ON ledger_entries (paypal_order_id);
CREATE INDEX ledger_capture ON ledger_entries (paypal_capture_id);

-- A tug is a request waiting for the human.
CREATE TABLE tugs (
    id           VARCHAR(80) PRIMARY KEY,
    entry_id     VARCHAR(80) NOT NULL UNIQUE REFERENCES ledger_entries (id),
    status       VARCHAR(12) NOT NULL,                   -- PENDING / APPROVED / DENIED
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX tugs_status ON tugs (status);
