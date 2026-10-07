-- V4: reconciliation.
--
-- Reconciliation never mutates ledger or settlement rows. It only records what
-- it observed. Mismatches stay visible until explicitly resolved — hiding them
-- would defeat the purpose.

CREATE TABLE reconciliation_runs (
    id             UUID PRIMARY KEY,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    status         VARCHAR(16) NOT NULL, -- RUNNING | COMPLETED | FAILED
    checked_count  INT NOT NULL DEFAULT 0,
    mismatch_count INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_recon_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE reconciliation_mismatches (
    id             UUID PRIMARY KEY,
    run_id         UUID NOT NULL REFERENCES reconciliation_runs(id),
    mismatch_type  VARCHAR(48) NOT NULL,
    -- MISSING_SETTLEMENT | DUPLICATE_SETTLEMENT | AMOUNT_MISMATCH
    -- | CURRENCY_MISMATCH | UNEXPECTED_SETTLEMENT | LATE_SETTLEMENT
    transaction_id UUID REFERENCES ledger_transactions(id),
    settlement_id  UUID REFERENCES settlements(id),
    details        JSONB NOT NULL DEFAULT '{}',
    resolved_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_recon_mismatch_run ON reconciliation_mismatches(run_id);
CREATE INDEX ix_recon_mismatch_open ON reconciliation_mismatches(resolved_at)
    WHERE resolved_at IS NULL;
