# Reconciliation

Reconciliation answers: "does the outside world agree with our ledger?"
It compares every posted transfer against its settlement and reports
discrepancies — without ever hiding them.

## What it checks

| Mismatch | Meaning |
|---|---|
| `MISSING_SETTLEMENT` | posted transfer has no settlement |
| `AMOUNT_MISMATCH` | settlement amount ≠ ledger amount |
| `CURRENCY_MISMATCH` | settlement currency ≠ ledger currency |
| `LATE_SETTLEMENT` | settlement stuck in PENDING/PROCESSING past the threshold |
| `DUPLICATE_SETTLEMENT` | two settlements for one transaction (constraint normally prevents) |
| `UNEXPECTED_SETTLEMENT` | settlement with no ledger transaction (FK normally prevents) |

## Guarantees

- **Runs are idempotent:** an open mismatch is reported once; reruns don't
  duplicate it (`findOpen` check per type + entities).
- **Mismatches are never auto-resolved.** They stay open until a human with
  `ADMIN` resolves them, and resolution is audited.
- Every new mismatch emits `reconciliation.mismatch.detected.v1`.
- The reconciler never writes to ledger or settlement tables — it only
  observes and records.

## Running it

- Scheduled: every 60s (`ledgerflow.reconciliation.interval-ms`).
- On demand: `POST /api/v1/reconciliation/runs`.
- Late threshold: 120s default (`ledgerflow.reconciliation.late-threshold-seconds`).

## Demo recipe

1. Transfer with `"settlementScenario": "TIMEOUT"`.
2. Wait past the late threshold (or trigger a run).
3. `GET /api/v1/reconciliation/mismatches` → `LATE_SETTLEMENT`.
4. Resolve it as ADMIN; the resolution lands in `audit_log`.
