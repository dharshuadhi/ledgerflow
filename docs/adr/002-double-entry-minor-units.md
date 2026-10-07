# ADR-002: Double-Entry Bookkeeping with Minor-Unit Amounts

**Status:** Accepted

## Context

Money must never be created or destroyed by a software bug. A single
"balance" column updated by application code has no structural protection
against that.

## Decision

1. Every movement is recorded as ≥2 **postings** (debit/credit) under one
   **ledger transaction**, validated so debits == credits per currency before
   anything is persisted.
2. Amounts are stored as `BIGINT` **minor units** (cents), never `FLOAT`/`DOUBLE`.
   Binary floating point cannot represent most decimal fractions exactly;
   rounding drift would eventually break the debits == credits invariant.
3. `NUMERIC` is used only for FX *reference rates* — they are market data, not
   money, and every conversion rounds explicitly to minor units at use time.

## Alternatives

- **BigDecimal everywhere:** safe but slower and still requires explicit
  rounding discipline; minor-unit longs make "no fractions of a cent" a type-
  level property.
- **Single balance column with checks:** provides no audit trail and no
  structural invariant.

## Consequences

- The global invariant (`sum(debits) == sum(credits)` per currency) is
  checkable with one SQL query and is asserted by tests under concurrency.
- Corrections use reversal transactions; history is append-only and auditable.
