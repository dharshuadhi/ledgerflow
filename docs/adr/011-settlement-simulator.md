# ADR-011: Settlement Simulator Instead of a Real Provider

**Status:** Accepted

## Context

A portfolio ledger cannot move real money, but settlement failure handling
(timeouts, duplicate callbacks, outages) is where distributed-systems skill
actually shows. Faking a "bank API" with hardcoded success would teach nothing.

## Decision

A deterministic **simulator** with scripted scenarios (SUCCESS, FAIL, TIMEOUT,
DELAYED, DUPLICATE_CALLBACK, PROVIDER_OUTAGE), chosen per transfer via a
clearly-marked test hook. Every callback travels the production path: HMAC
signature, webhook endpoint, idempotent application, outbox events.

## Alternatives

- **Pretending to integrate a real provider:** dishonest and untestable.
  Rejected explicitly.
- **No settlement at all:** loses the richest failure-handling surface in the
  system. Rejected.

## Consequences

- The failure model is demonstrable and testable end-to-end.
- The README and docs state plainly that this is a simulator; swapping in a
  real provider means implementing the webhook contract against their API.
