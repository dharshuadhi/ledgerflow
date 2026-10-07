# ADR-004: At-Least-Once Delivery Everywhere

**Status:** Accepted

## Context

Kafka can lose or duplicate deliveries across relay restarts, consumer
rebalances, and network partitions. Designing for exactly-once end-to-end
would require idempotent producers + transactional consumers + broker
configuration acting in concert — fragile across restarts.

## Decision

Assume **at-least-once** delivery at every hop and make every consumer
idempotent:

- The outbox relay may republish after a crash; consumers deduplicate on the
  event id (`processed_events` table, `event-id` Kafka header).
- The balance projection is a commutative fold (addition), so redelivery and
  reordering are both harmless.
- Webhook callbacks deduplicate on the provider's event id under a row lock.

## Alternatives

- **Exactly-once via Kafka transactions:** rejected — it doesn't survive the
  outbox relay's crash-republish pattern without heroic effort, and the
  business cost of a duplicate is already zero because consumers are
  idempotent.

## Consequences

- Simpler, more robust failure handling; duplicates are expected and cheap.
- Tests explicitly cover redelivery (duplicate webhooks, relay restarts).
