# ADR-007: Optimistic Locking for Concurrent Transfers

**Status:** Accepted

## Context

Two transfers racing on the same account must not lose updates or
double-spend. Options: pessimistic row locks (`SELECT ... FOR UPDATE`) or
optimistic version checks with retry.

## Decision

Optimistic locking on `accounts.version`, with bounded retries (4 attempts,
jittered backoff) in the transfer service. A lost update surfaces as
`OptimisticLockException` instead of silent corruption; the retry usually
succeeds because transfers are short.

## Alternatives

- **Pessimistic locking:** correct and simpler to reason about, but serializes
  all transfers on hot accounts and risks lock waits under load. Rejected for
  the write path; pessimistic locks are still used where check-then-act must
  be atomic (webhook dedup, outbox row claiming).

## Consequences

- Hot accounts contend: retries are metered (`ledgerflow.transfer.duration`
  percentiles) and exercised by the k6 hot-account script.
- Retry storms are bounded (max 4 attempts, then a clean 409 for the client).
