# Events

## Contracts

All events are versioned JSON. Rules:

- `eventId` is globally unique and is the **consumer deduplication key**.
- `eventType` embeds its version: `ledger.transaction.posted.v1`.
- New **optional** fields are backward compatible. Removing or retyping a
  field requires a new version (new topic).
- Payloads are **self-contained** — a consumer never needs to call back into
  the API to act.

| Event | Topic | Key | Produced by |
|---|---|---|---|
| `ledger.transaction.posted.v1` | `ledger.events.v1` | transaction id | outbox relay |
| `settlement.requested.v1` | `settlement.events.v1` | settlement id | outbox relay |
| `settlement.completed.v1` | `settlement.events.v1` | settlement id | outbox relay |
| `settlement.failed.v1` | `settlement.events.v1` | settlement id | outbox relay |
| `reconciliation.mismatch.detected.v1` | `reconciliation.events.v1` | run id | outbox relay |
| *(poison)* | `ledger.events.dlq` | aggregate id | outbox relay |

Example — `ledger.transaction.posted.v1`:

```json
{
  "eventId": "…",
  "eventType": "ledger.transaction.posted.v1",
  "eventVersion": 1,
  "occurredAt": "2026-10-06T…",
  "transactionId": "…",
  "transactionType": "TRANSFER",
  "idempotencyKey": "…",
  "metadata": { "settlementScenario": "SUCCESS" },
  "postings": [
    { "accountId": "…", "direction": "DEBIT", "amountMinorUnits": 2500, "currency": "USD" },
    { "accountId": "…", "direction": "CREDIT", "amountMinorUnits": 2500, "currency": "USD" }
  ]
}
```

## Partition strategy

**Key = aggregate id.** All events for one aggregate keep partition order
(ADR-006). The projection doesn't need cross-transaction ordering because
applying postings is a commutative fold.

## Consumer groups

| Group | Topic | Behavior |
|---|---|---|
| `ledgerflow-projection` | `ledger.events.v1` | folds postings into `balance_projections`; dedups on event id |
| `ledgerflow-settlement` | `ledger.events.v1` | creates settlements; dedups on event id |
| `ledgerflow-settlement-sim` | `settlement.events.v1` | drives the simulator |

All use **manual acknowledgment**: offsets commit only after the database
work commits. A crash between "applied" and "acknowledged" replays an
already-recorded event id — a no-op.

## Retry and dead letters

- Relay: exponential backoff (1s→…→cap), 10 attempts, then POISON + DLQ copy.
- Consumers: Spring Kafka retries on failure; persistent poison is a DLQ +
  alerting concern (documented, not silently dropped).
