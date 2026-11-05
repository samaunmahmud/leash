-- Mandate learning (T16). A leash change is the human accepting a suggestion: the audit trail for "why is my slack
-- £30 now?". It shows in the ledger next to the purchases that led to it.
CREATE TABLE leash_changes (
    id           VARCHAR(80)  PRIMARY KEY,
    agent_id     VARCHAR(64)  NOT NULL REFERENCES agents (id),
    kind         VARCHAR(24)  NOT NULL,                  -- SLACK_RAISED
    from_minor   BIGINT       NOT NULL,
    to_minor     BIGINT       NOT NULL,
    currency     CHAR(3)      NOT NULL,
    summary      VARCHAR(500) NOT NULL,                  -- the sentence the human accepted
    evidence     VARCHAR(500) NOT NULL,                  -- the computed facts behind it, worded by code
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX leash_changes_agent_time ON leash_changes (agent_id, created_at);

-- "Not now" on a suggestion: the same raise isn't offered again (a different one, from new evidence, can be).
CREATE TABLE suggestion_dismissals (
    agent_id     VARCHAR(64)  NOT NULL REFERENCES agents (id),
    from_minor   BIGINT       NOT NULL,
    to_minor     BIGINT       NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (agent_id, from_minor, to_minor)
);
