-- V9: outbox_events.last_error was in the entity but missing from V2.
ALTER TABLE outbox_events ADD COLUMN last_error TEXT;
