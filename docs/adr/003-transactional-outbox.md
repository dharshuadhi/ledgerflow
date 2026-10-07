# ADR-003: Transactional Outbox for Reliable Event Publishing

**Status:** Accepted

## Context

After committing ledger postings, the system must notify downstream consumers
(settlement, balance projection) via Kafka. Publishing directly inside the
request risks the dual-write problem: the DB commits but the publish fails
(or vice versa), leaving the system inconsistent with no record of what was
lost.

## Decision

Write the event to an `outbox_events` table **in the same database transaction**
as the business rows. A relay publisher drains PENDING rows to Kafka with
exponential backoff, poison-event quarantining, and a dead-letter topic.

## Alternatives

- **Publish-then-commit / commit-then-publish:** both leave a failure window
  with silent data loss. Rejected.
- **XA / distributed transactions:** operational complexity and poor Kafka
  integration. Rejected.
- **Debezium CDC:** excellent at scale, but adds Kafka Connect infrastructure
  for a problem a 100-line relay solves. Documented as the scale-up path.

## Consequences

- Publishing is reliable without distributed transactions.
- Delivery is at-least-once (ADR-004); consumers deduplicate.
- The relay is stateless: crashes just delay publishing, never lose events.
