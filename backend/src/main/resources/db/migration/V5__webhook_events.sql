-- Every PayPal webhook delivery that passed signature verification, once. PayPal retries deliveries (and can send the
-- same event twice), so the event ID is the key: a repeat is acknowledged and skipped.

CREATE TABLE webhook_events (
    id           VARCHAR(80)  PRIMARY KEY,               -- PayPal's event ID (WH-...)
    event_type   VARCHAR(80)  NOT NULL,
    resource_id  VARCHAR(80),                            -- the capture / refund / order the event is about
    entry_id     VARCHAR(80),                            -- the ledger entry it changed, if any
    result       VARCHAR(200) NOT NULL,                  -- what Leash did with it, in a few words
    received_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
