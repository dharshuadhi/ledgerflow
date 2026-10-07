# Scaling

Honest scale story: this repo is built for correct single-node operation with
a clear path upward. Nothing here pretends to run at 1M users today.

## 100 users — today

- `docker compose up`: one app replica, Postgres, Redpanda, Redis.
- Everything in this repo works as-is.

## 10,000 users

What changes and why:

- **App replicas (2–3):** stateless; the outbox relay already shares work via
  `SKIP LOCKED`, and Kafka consumer groups rebalance automatically.
- **Kafka partitions:** raise `ledger.events.v1` to 6–12 partitions so
  projection/settlement consumers parallelize.
- **Postgres read replica:** statement/history queries move to the replica;
  writes stay on the primary.
- **Redis:** already external; no change.

What does NOT change: the ledger write path, the outbox pattern, the event
contracts.

## 1,000,000 users

- **Postgres:** primary starts straining on the write path. Options in order:
  1. Bigger primary + aggressive indexing (often enough).
  2. Partition `postings` by month (`created_at`); statements hit one partition.
  3. Separate the outbox relay into its own deployable (it already has no
     shared state with the API).
- **Hot accounts:** optimistic retries degrade past a point. Mitigations:
  account sharding for the handful of truly hot accounts, or partitioning
  the balance cache per account shard.
- **Kafka:** multi-broker cluster; exactly-once producer settings already on.
- **Kubernetes:** justified here (ADR-010) — independent scaling of API,
  relay, and consumer groups; the liveness/readiness endpoints are already
  shaped for it.
- **CDC:** replace the polling relay with Debezium when poll overhead matters.

## What never changes with scale

The invariants: double-entry, idempotency, at-least-once with idempotent
consumers, postings as truth. Scale changes topology, not correctness.
