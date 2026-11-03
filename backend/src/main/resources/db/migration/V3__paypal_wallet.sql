-- The human's saved PayPal wallet (Vault v3), so approved purchases are paid with no popup.
-- One row: Leash has one owner until real accounts exist (cut list).
-- Deliberately stores only PayPal's token IDs and a masked email hint. No email, name, payer ID or customer ID.

CREATE TABLE paypal_wallet (
    id                VARCHAR(16)  PRIMARY KEY,          -- always 'owner'
    status            VARCHAR(12)  NOT NULL,             -- PENDING (waiting for approval) / ACTIVE
    setup_token_id    VARCHAR(64),                       -- while PENDING: the setup token the owner approves
    approve_url       VARCHAR(500),                      -- while PENDING: PayPal's approve link for that token
    payment_token_id  VARCHAR(64),                       -- once ACTIVE: the vault ID orders are paid with
    hint              VARCHAR(120),                      -- e.g. "s•••@personal.example.com", shown in the dashboard
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL
);
