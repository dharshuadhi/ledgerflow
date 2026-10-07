# Performance

## Methodology

- Benchmarks are **executed**, not estimated. The k6 scripts in
  `load-tests/k6/` run against a real stack (`docker compose up`).
- Each script writes its summary to `load-tests/k6/results/`. Those files are
  generated artifacts — do not hand-edit them.
- Report environment alongside numbers (CPU, Postgres/Redpanda placement);
  numbers without context are marketing.

## What we measure

| Metric | Source |
|---|---|
| Transfer throughput | k6 soak iterations/s |
| Transfer p50/p95/p99 | `ledgerflow.transfer.duration` histogram |
| HTTP p95 by endpoint | `http_server_requests_seconds` |
| Outbox backlog | `ledgerflow.outbox.backlog` gauge |
| Hot-account behavior | k6 hot-account script + retry counts in logs |
| Invariant under load | teardown assertions (cache == postings) |

## Known contention points

1. **Hot accounts** — optimistic-lock retries on `accounts.version`. Bounded
   (4 attempts, jittered); beyond that the client gets a clean 409 and retries
   with the same idempotency key.
2. **Outbox relay poll** — single-threaded, batch 100, 1s poll. Fine to
   thousands of transfers/s; scale by partitioning the claim or adding relay
   instances (`SKIP LOCKED` already makes that safe).
3. **Projection consumer** — single partition consumption per group; the fold
   is O(postings per event), trivially parallelizable by partition.

## Scaling levers (in order)

1. More app replicas (stateless; relay/consumer groups coordinate via Kafka).
2. More Kafka partitions for `ledger.events.v1`.
3. Read replicas for statement queries.
4. Table partitioning on `postings.created_at` when the table outgrows
   comfortable index sizes.

See docs/scaling.md for the 100 → 10k → 1M user story.
