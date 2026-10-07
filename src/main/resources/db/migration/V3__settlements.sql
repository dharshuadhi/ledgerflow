-- V3: settlements.
--
-- A settlement tracks the lifecycle of moving ledger money through an external
-- (simulated) provider. provider_event_id is the webhook deduplication key:
-- the provider may deliver the same callback twice, and the second delivery
-- must be a no-op.

CREATE TABLE settlements (
    id                UUID PRIMARY KEY,
    transaction_id    UUID NOT NULL REFERENCES ledger_transactions(id),
    amount_minor_units BIGINT NOT NULL,
    currency          CHAR(3) NOT NULL,
    status            VARCHAR(16) NOT NULL, -- PENDING | PROCESSING | SETTLED | FAILED
    provider_ref      VARCHAR(128),
    provider_event_id VARCHAR(128),
    scenario          VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    attempts          INT NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at        TIMESTAMPTZ,
    not_before        TIMESTAMPTZ,
    CONSTRAINT uq_settlement_tx UNIQUE (transaction_id),
    CONSTRAINT chk_settlement_amount_pos CHECK (amount_minor_units > 0),
    CONSTRAINT chk_settlement_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SETTLED', 'FAILED'))
);
-- NULL provider_event_ids are distinct under the unique index; only real
-- provider ids deduplicate.
CREATE UNIQUE INDEX uq_settlement_provider_event
    ON settlements(provider_event_id) WHERE provider_event_id IS NOT NULL;
CREATE INDEX ix_settlements_status ON settlements(status);
