# ADR-005: Balances Are Projections, Not Stored Truth

**Status:** Accepted

## Context

Reads need fast balances, but a mutable `balance` column is a second source of
truth that can diverge from the posting history — the classic "cache vs.
reality" split-brain.

## Decision

- `accounts.balance_minor_units` is a **maintained cache** updated in the same
  transaction as the postings (optimistic locking guards lost updates).
- `balance_projections` is a **derived read model** folded from Kafka events.
- Both are verified against `sum(postings)`: the `/balances` endpoint returns
  all three, and a verifier reports divergence instead of hiding it.

## Alternatives

- **Compute from postings on every read:** correct but O(history) per read;
  fine at small scale, painful for statements. The cache keeps reads O(1).
- **Balance column as truth:** rejected — it makes the audit trail
  unverifiable.

## Consequences

- Two caches to maintain, but each is rebuildable and each is checked.
  Divergence is a first-class observable event, not a silent bug.
