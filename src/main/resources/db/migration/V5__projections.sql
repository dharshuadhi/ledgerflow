-- V5: read-model projections, consumer checkpoints, event deduplication.
--
-- balance_projections is a DERIVED read model. It can be dropped and rebuilt
-- from postings at any time; nothing authoritative reads from it.
--
-- processed_events gives consumers idempotent handling under at-least-once
-- delivery: (event_id) is the dedup key.

CREATE TABLE balance_projections (
    account_id          UUID PRIMARY KEY REFERENCES accounts(id),
    currency            CHAR(3) NOT NULL,
    balance_minor_units BIGINT NOT NULL,
    last_event_id       UUID,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE projection_checkpoints (
    consumer_group  VARCHAR(128) NOT NULL,
    topic_partition INT NOT NULL,
    last_offset     BIGINT NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, topic_partition)
);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    consumer     VARCHAR(128) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_processed_events_consumer ON processed_events(consumer, processed_at);
