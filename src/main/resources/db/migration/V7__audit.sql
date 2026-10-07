-- V7: audit log.
--
-- Append-only record of security-sensitive operations (account freezes,
-- reversals, mismatch resolutions, projection rebuilds). Written by the
-- application, never updated.

CREATE TABLE audit_log (
    id            UUID PRIMARY KEY,
    actor         VARCHAR(128) NOT NULL,
    action        VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64),
    resource_id   VARCHAR(64),
    details       JSONB NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_actor_created ON audit_log(actor, created_at DESC);
CREATE INDEX ix_audit_resource ON audit_log(resource_type, resource_id);
