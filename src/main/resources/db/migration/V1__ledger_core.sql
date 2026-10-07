-- V1: core double-entry ledger tables.
--
-- Design notes:
-- * Money is stored as BIGINT minor units (never floating point). See ADR-002.
-- * postings is append-only by convention (no update/delete path in code);
--   corrections use reversal transactions.
-- * accounts.balance_minor_units is a maintained cache, NOT authoritative.
--   The authoritative balance is sum(postings). A verifier job reconciles both.

CREATE TABLE accounts (
    id                 UUID PRIMARY KEY,
    account_number     VARCHAR(32) NOT NULL UNIQUE,
    owner_name         VARCHAR(128) NOT NULL,
    currency           CHAR(3) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    balance_minor_units BIGINT NOT NULL DEFAULT 0,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_account_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_account_balance_nonneg CHECK (balance_minor_units >= 0)
);
CREATE INDEX ix_accounts_status ON accounts(status);

CREATE TABLE ledger_transactions (
    id              UUID PRIMARY KEY,
    type            VARCHAR(16) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(64),
    description     VARCHAR(512),
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ledger_tx_idempotency UNIQUE (idempotency_key)
    -- NULL idempotency keys are not considered equal by the unique index,
    -- so non-idempotent flows can leave it NULL.
);
CREATE INDEX ix_ledger_tx_type ON ledger_transactions(type);
CREATE INDEX ix_ledger_tx_status ON ledger_transactions(status);

CREATE TABLE postings (
    id                 UUID PRIMARY KEY,
    transaction_id     UUID NOT NULL REFERENCES ledger_transactions(id),
    account_id         UUID NOT NULL REFERENCES accounts(id),
    direction          VARCHAR(8) NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    currency           CHAR(3) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_posting_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_posting_amount_pos CHECK (amount_minor_units > 0),
    CONSTRAINT chk_posting_currency CHECK (currency ~ '^[A-Z]{3}$')
);
-- Lookup postings of one transaction (statement rendering, reversal).
CREATE INDEX ix_postings_transaction ON postings(transaction_id);
-- Statement/history queries: one account, newest first.
CREATE INDEX ix_postings_account_created ON postings(account_id, created_at DESC);
