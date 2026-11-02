-- A leash is an agent plus the mandate it spends under. One mandate per agent for now.

CREATE TABLE agents (
    id          VARCHAR(64)  PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL,
    kind        VARCHAR(32)  NOT NULL,           -- grocery / office / travel / other: picks the UI identity colour
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at  TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE mandates (
    id                        VARCHAR(64)  PRIMARY KEY,
    agent_id                  VARCHAR(64)  NOT NULL UNIQUE REFERENCES agents (id),
    name                      VARCHAR(120) NOT NULL,
    currency                  CHAR(3)      NOT NULL,
    period                    VARCHAR(8)   NOT NULL,
    period_limit_minor        BIGINT       NOT NULL CHECK (period_limit_minor > 0),
    auto_approve_up_to_minor  BIGINT       NOT NULL CHECK (auto_approve_up_to_minor >= 0),
    per_purchase_max_minor    BIGINT       NOT NULL CHECK (per_purchase_max_minor >= 0),
    -- Sets are stored one value per line, lower-cased (the domain normalises them anyway).
    allowed_merchants         TEXT         NOT NULL DEFAULT '',
    blocked_merchants         TEXT         NOT NULL DEFAULT '',
    allowed_categories        TEXT         NOT NULL DEFAULT '',
    blocked_categories        TEXT         NOT NULL DEFAULT '',
    max_purchases_per_hour    INT          NOT NULL DEFAULT 0,
    quiet_start               VARCHAR(5),
    quiet_end                 VARCHAR(5),
    active                    BOOLEAN      NOT NULL DEFAULT TRUE,
    source_text               TEXT,                       -- the plain-English mandate the human wrote, if any
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL
);
