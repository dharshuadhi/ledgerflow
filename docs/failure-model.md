# Failure Model

For each failure: what breaks, what the client sees, what is retried, what is
persisted, whether duplicates can occur, and how we verify recovery.

## Kafka unavailable

- **What fails:** outbox relay publishes fail.
- **Client impact:** none — transfers still commit (postings + outbox row are
  local to Postgres).
- **Retry:** exponential backoff per event; relay keeps polling.
- **Duplicates:** possible on recovery (redelivery) — consumers dedup.
- **Verify:** `ledgerflow.outbox.backlog` returns to 0; consumer lag recovers.

## PostgreSQL restart

- **What fails:** everything pauses; in-flight transactions roll back.
- **Client impact:** 5xx / connection errors until the DB is back.
- **Retry:** client retries with the same idempotency key → either replays
  (if the first attempt committed) or executes fresh (if it rolled back).
- **Verify:** Flyway validates schema on startup; invariant checks pass.

## Outbox relay crash

- **What fails:** publishing stops; rows stay PENDING.
- **Duplicates:** the next relay republishes anything unacknowledged.
- **Verify:** backlog drains; no event id appears twice in `processed_events`.

## Consumer killed mid-processing

- **What fails:** offset not acknowledged.
- **Recovery:** rebalance redelivers; event-id dedup makes it a no-op.
- **Verify:** projection balances unchanged by the redelivery.

## Settlement provider timeout

- **What fails:** no webhook arrives; settlement stays PROCESSING.
- **Client impact:** transfer already succeeded (settlement is async by design).
- **Recovery:** reconciler flags `LATE_SETTLEMENT`; ops investigate.
- **Verify:** mismatch appears in `/reconciliation/mismatches`.

## Duplicate settlement webhook

- **What fails:** nothing — second delivery hits the `provider_event_id`
  dedup under a row lock and returns `duplicate`.
- **Verify:** exactly one `settlement.completed` event; balance unchanged.

## Projection corruption

- **What fails:** read model diverges from postings.
- **Recovery:** rebuild from postings (tested path) — the table is derived.
- **Verify:** `/accounts/{id}/balances` shows `cacheConsistent: true`.

## Reconciliation mismatch

- **What fails:** nothing automatically — mismatches are recorded, events
  emitted, and they stay open until a human resolves them.
- **Verify:** mismatch visible via API and Grafana; resolution is audited.
