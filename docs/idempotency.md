# Idempotency

Money-moving endpoints must be safely retryable: networks fail, clients time
out, and "did it go through?" must always have a safe answer.

## Protocol

1. Client generates a unique `Idempotency-Key` per logical operation and sends
   it in the header.
2. The server `INSERT`s the key with status `IN_PROGRESS`. The primary key is
   the concurrency primitive — racing duplicates collide, exactly one wins.
3. The winner executes; the response (status + body) is persisted.
4. Retries with the same key **replay** the stored response — money never
   moves twice.

## Behavior matrix

| Situation | Response |
|---|---|
| Same key, same request body | Stored response replayed (`Idempotent-Replay: true`) |
| Same key, different body | `422` — a key identifies one request |
| Same key, first request in flight | Wait up to ~4s, then `409` if still running |
| First attempt failed | The failure is replayed deterministically (same status) |
| Response lost after commit | Retry replays the committed result |

## Request identity

"Same request" is determined by SHA-256 over the canonical JSON
(sorted keys) of the operation fields — not by byte equality, so formatting
differences don't cause false conflicts.

## Lifecycle

- Keys live 24h (`expires_at`), cleaned by a scheduled job.
- `FAILED` records replay the failure rather than re-executing: for money,
  deterministic failure beats a second attempt with unknown side effects.

## What idempotency does NOT cover

- Two different keys for the same logical transfer are two transfers. Key
  generation discipline is the client's responsibility.
- Idempotency is per-endpoint: it guards the transfer operation, not the
  settlement lifecycle (which has its own dedup via provider event ids).
