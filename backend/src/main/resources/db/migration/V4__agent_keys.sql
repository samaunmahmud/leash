-- One secret key per agent, used by MCP clients and the REST purchase endpoint (Authorization: Bearer lk_...).
-- Only a SHA-256 hash is stored, so a database leak doesn't leak working keys. The key itself is shown once.

CREATE TABLE agent_keys (
    agent_id    VARCHAR(64) PRIMARY KEY REFERENCES agents (id),
    key_hash    CHAR(64)    NOT NULL UNIQUE,              -- hex SHA-256 of the full key
    key_prefix  VARCHAR(12) NOT NULL,                     -- first characters, so the dashboard can say which key is live
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
