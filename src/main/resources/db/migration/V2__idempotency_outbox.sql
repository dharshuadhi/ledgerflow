-- V2: idempotency records and transactional outbox.
--
-- Idempotency: one row per client-supplied key. The INSERT is the concurrency
-- primitive — two racing requests collide on the PK and exactly one wins.
-- Responses are persisted so a retry after a lost response replays identically.
--
-- Outbox: business rows and outbox rows commit in ONE database transaction.
-- A relay publisher drains PENDING rows to Kafka. At-least-once delivery is
-- assumed; consumers deduplicate on event id.

CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(64) PRIMARY KEY,
    request_hash   VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL, -- IN_PROGRESS | COMPLETED | FAILED
    response_status INT,
    response_body  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_idem_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);
CREATE INDEX ix_idempotency_expires ON idempotency_keys(expires_at);

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    VARCHAR(64) NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    event_version   INT NOT NULL DEFAULT 1,
    payload         JSONB NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING | PUBLISHED | POISON
    publish_attempts INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'POISON'))
);
-- Relay poll: due rows first; SKIP LOCKED lets multiple relay instances share work.
CREATE INDEX ix_outbox_drain ON outbox_events(status, next_attempt_at)
    WHERE status = 'PENDING';
CREATE INDEX ix_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id);
