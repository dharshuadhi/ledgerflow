# ADR-006: Kafka Partition Strategy

**Status:** Accepted

## Context

Partition keys determine ordering guarantees and scaling. Wrong keys cause
either hot partitions or ordering violations.

## Decision

- **Key = aggregate id** (transaction id for ledger events, settlement id for
  settlement events). All events for one aggregate land in one partition, in
  offset order.
- The balance projection does **not** rely on cross-transaction ordering:
  applying postings is a commutative fold (addition), so redelivery and
  reordering are harmless. This is documented, not assumed.
- Topic names are versioned (`ledger.events.v1`); breaking contract changes
  ship as new topics.

## Alternatives

- **Key by account id:** would give per-account ordering, but one transaction
  touches two accounts — it would need two events per transfer and a join on
  the consumer side. Rejected as complexity without benefit, given the
  commutative projection.

## Consequences

- Consumers must still tolerate redelivery (they do, via event-id dedup).
- Scaling out the projection consumer is safe: partitions can be split across
  instances with no cross-talk.
