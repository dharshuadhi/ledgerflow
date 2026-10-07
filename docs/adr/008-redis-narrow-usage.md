# ADR-008: Redis Usage — Deliberately Narrow

**Status:** Accepted

## Context

Redis is tempting as a balance cache or idempotency store, but every
Redis-held copy of authoritative state is a consistency liability.

## Decision

Redis is used for **exactly one thing**: rate-limit token buckets. It is
deliberately NOT used for:

- **Balances** — authoritative balances live in postings (ADR-005). A Redis
  balance cache would be a third source of truth.
- **Idempotency records** — they must survive restarts and be transactionally
  consistent with the business write; they live in PostgreSQL.
- **Outbox relay leadership** — single relay uses `SKIP LOCKED`; multi-instance
  would use a Postgres advisory lock, not Redis.

## Consequences

- Losing Redis degrades to fail-open rate limiting (logged, metered) — it can
  never corrupt money.
- If Redis is removed entirely, the only loss is rate limiting.
