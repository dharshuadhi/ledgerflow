# ADR-001: PostgreSQL as the Authoritative Store

**Status:** Accepted

## Context

LedgerFlow needs one place where "what happened" is indisputable: balances,
transfers, settlement state. Candidates: PostgreSQL, a NoSQL store, or the
Kafka log itself.

## Decision

PostgreSQL is the system of record for all business state. Kafka carries
notifications of state changes; Redis carries only ephemeral rate-limit
buckets. The balance projection tables are derived read models.

## Alternatives

- **Kafka as source of truth (event sourcing):** powerful but the team operating
  burden (schema registry, compaction, replay tooling, exactly-once sinks) is
  disproportionate for a ledger whose read patterns are simple point lookups
  and statements. Rejected for now; the outbox + projection design keeps the
  door open.
- **MongoDB / Cassandra:** weaker transactional guarantees across documents;
  the ledger's core operation (postings + outbox in one ACID transaction) maps
  naturally to a relational database.

## Consequences

- All money movement is ACID. The transactional outbox (ADR-003) piggybacks on
  the same transaction.
- We accept single-writer-database scaling limits; sharding/partitioning is a
  documented future step (docs/scaling.md), not day-one complexity.
