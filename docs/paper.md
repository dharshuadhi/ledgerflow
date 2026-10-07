# LedgerFlow: An Auditable Event-Driven Payment Ledger with Idempotent APIs and Replayable State

**Author:** Dharshini Adimoolam
**Date:** October 2026
**Stack:** Java 21 · Spring Boot 3.2 · PostgreSQL 16 · Redpanda (Kafka API) · Redis · Flyway · OpenTelemetry

---

## Abstract

LedgerFlow is a production-style money-movement system that implements the
core patterns real payment infrastructure depends on: double-entry
bookkeeping with structural balance invariants, idempotent APIs that survive
retries and response loss, a transactional outbox for reliable event
publication, at-least-once consumers with idempotent handling, asynchronous
settlement with realistic failure modes, and independent reconciliation that
reports — never hides — discrepancies. Balances are maintained as rebuildable
projections over an append-only posting history; the ledger itself is never
rewritten.

This paper describes the system's architecture, the protocols that keep money
correct under concurrency and failure, the engineering trade-offs chosen at
each layer, and what was verified versus what remains to be proven under load.

---

## 1. Introduction: Why Moving Money Is Hard

Transferring money between accounts looks trivial until the network is
involved. Then every operation becomes a distributed transaction across
systems that fail independently:

- A client times out waiting for a response. Did the transfer happen?
  Retrying blindly risks double-spending; not retrying risks losing the
  payment.
- Two requests debit the same account simultaneously. Without concurrency
  control, one update silently overwrites the other — money appears from
  nowhere or vanishes.
- The ledger says a transfer posted, but the downstream settlement provider
  never confirmed. Which system is right?
- A settlement webhook arrives twice. Is the second delivery a duplicate, or
  a second payment?

Production payment systems answer these questions with a small set of
battle-tested patterns: **idempotency keys**, **double-entry bookkeeping**,
**transactional outbox**, **at-least-once delivery with idempotent
consumers**, and **reconciliation**. LedgerFlow implements all five in one
coherent, runnable codebase — not as isolated demos, but as an integrated
system where each pattern covers the failure modes the others leave open.

---

## 2. Goals and Non-Goals

### Goals

1. **Correctness first.** Money must never silently appear or disappear.
   Every invariant is enforced structurally (in the schema or domain model),
   not by convention.
2. **Retry safety.** Any failed or timed-out request can be retried with the
   same key and produce exactly one effect.
3. **Auditability.** The full history of money movement is preserved and
   queryable. Corrections are reversals, never edits.
4. **Failure realism.** The settlement layer fails the way real providers
   fail — timeouts, duplicates, outages — so the system's handling of those
   failures is genuinely exercised.
5. **Observability.** Every important behavior emits metrics, traces, and
   structured logs.

### Non-Goals

- LedgerFlow is not a bank and does not connect to one. Settlement is an
  explicit, deterministic **simulator** — the failure handling is real, the
  counterparty is not.
- It is not a high-frequency trading engine. The design targets correct
  single-node operation with a clear scaling path, not microsecond latency.
- It does not implement cross-currency transfers yet; FX conversion is
  quote-only.

---

## 3. System Architecture

```
Client
  │  POST /api/v1/transfers  (Idempotency-Key header)
  ▼
API Layer (controllers, validation, RFC 7807 errors)
  │
  ▼
TransferService ──► IdempotencyService (key claim / replay)
  │
  ▼
TransferExecutor ──► LedgerService (double-entry postings)
  │                        │
  │                        ▼
  │                   PostgreSQL (postings + outbox row, ONE transaction)
  ▼
OutboxRelay ──► Redpanda/Kafka ──► Settlement consumer ──► Settlement simulator
                             │
                             └─► Projection consumer ──► balance_projections

Reconciler (scheduled) ──► compares ledger vs settlements ──► mismatches
```

The architecture is a **modular monolith**: one deployable with strict
module boundaries (ledger, transfer, idempotency, outbox, events,
projection, settlement, reconciliation, fx, security, api, common). Each
module owns its tables and exposes a narrow service interface. The seams are
drawn where microservices would split if scale ever justified it — but a
single deployable is the right choice today (see §17).

### Request Flow: A Transfer's Lifecycle

1. Client sends `POST /api/v1/transfers` with an `Idempotency-Key` header.
2. `TransferService` hashes the canonical request and attempts to claim the
   key (§5).
3. On winning the claim, `TransferExecutor` runs **one database
   transaction**: validate accounts, write balanced postings, update cached
   balances, and stage the outbox event. All four commit atomically — or all
   roll back.
4. The response (status + body) is persisted against the idempotency key.
5. `OutboxRelay` picks up the staged event and publishes it to
   `ledger.events.v1`.
6. The **settlement consumer** creates a settlement row; the **simulator**
   behaves according to the requested scenario and eventually delivers a
   signed webhook.
7. The **projection consumer** folds the postings into `balance_projections`.
8. The **reconciler** periodically verifies that every posted transfer has a
   matching, correct settlement.

---

## 4. Data Model: Double-Entry Bookkeeping

### 4.1 Why Double-Entry

Single-entry systems record "account A: −$25." Double-entry systems record
two postings: "debit A $25, credit B $25." The invariant —
**sum(debits) == sum(credits) per transaction, per currency** — is then a
structural property that can be checked mechanically. Money cannot appear or
disappear without breaking the invariant loudly.

### 4.2 Schema

**accounts** — `id (UUID)`, `account_number (unique)`, `owner_name`,
`currency`, `status`, `balance_minor_units (BIGINT)`,
`version (BIGINT, optimistic lock)`. The balance column is a **maintained
cache**, not truth. Truth is `SUM(postings)`.

**ledger_transactions** — `id`, `type`, `status`, `idempotency_key (unique,
nullable)`, `description`, `metadata (JSONB)`.

**postings** — `id`, `transaction_id → ledger_transactions`,
`account_id → accounts`, `direction (DEBIT|CREDIT)`,
`amount_minor_units (BIGINT, > 0)`, `currency`. Append-only by convention;
corrections are reversal transactions.

**idempotency_keys** — the key-claim table (§5).

**outbox_events** — staged domain events (§6).

**settlements** — `transaction_id (unique)`, `amount_minor_units`,
`currency`, `status (PENDING|PROCESSING|SETTLED|FAILED)`,
`provider_ref`, `provider_event_id (unique, nullable)`, `scenario`,
`attempts`, timestamps.

**reconciliation_runs / reconciliation_mismatches** — §10.

**balance_projections / processed_events / projection_checkpoints** — §8.

**fx_rates** — §11. **audit_log** — §12.

### 4.3 Money Representation

All authoritative amounts are `BIGINT` minor units (cents). The `Money`
value object is immutable, validates ISO 4217 currency codes, uses exact
`long` arithmetic, and refuses to construct invalid values. Floating point
never touches money — not in the domain, not in the schema, not in events.
(FX *rates* are `NUMERIC`, because rates are reference data, not money;
conversion rounds to minor units explicitly with `HALF_EVEN` at use time.)

---

## 5. Idempotency Protocol

### 5.1 The Problem

Networks fail after the server commits but before the client receives the
response. The client cannot distinguish "failed before commit" from "failed
after commit." The only safe protocol: make retries idempotent.

### 5.2 The Protocol

1. The client generates a unique key per logical operation and sends it in
   the `Idempotency-Key` header (1–64 characters, validated).
2. The server computes `SHA-256` over the **canonical JSON** of the request
   (sorted keys — formatting differences don't cause false conflicts) and
   attempts `INSERT` into `idempotency_keys` with status `IN_PROGRESS`.
   The primary key is the concurrency primitive: racing duplicates collide,
   exactly one wins.
3. The winner executes the transfer, then persists the response
   (`COMPLETED` + status + body, or `FAILED` + error).
4. Any retry with the same key and same body **replays the stored response**
   byte-for-byte, with an `Idempotent-Replay: true` header. Money never
   moves twice.

### 5.3 Behavior Matrix

| Situation | Behavior |
|---|---|
| Same key, same body | Stored response replayed |
| Same key, different body | `422` — a key identifies one request |
| Same key, first request in flight | Wait up to 4s for settlement, else `409` |
| First attempt failed | The failure is replayed deterministically |
| Response lost after commit | Retry replays the committed result |

Failed attempts replay their failure rather than re-executing: for money,
deterministic failure beats a second attempt with unknown side effects.
Keys expire after 24 hours and are cleaned by a scheduled job.

### 5.4 Why INSERT-as-Claim

Alternatives considered: distributed locks (Redis Redlock) add a failure
domain and clock assumptions; `SELECT … FOR UPDATE` requires the row to
exist first. The primary-key insert is atomic, needs no extra
infrastructure, and its failure mode (duplicate key) is exactly the signal
we need.

---

## 6. Transactional Outbox and Event Delivery

### 6.1 The Dual-Write Problem

Publishing to Kafka *and* committing to Postgres cannot be atomic across
both systems. Publishing first risks events without ledger rows (phantom
money movement); committing first risks ledger rows without events (silent
money movement). The outbox pattern resolves this: the event is staged as
a row in the **same database transaction** as the postings. A relay then
publishes staged rows to Kafka. If the relay crashes, rows stay `PENDING`
and are picked up on recovery. If publishing succeeds but the relay crashes
before marking `PUBLISHED`, the event is republished — consumers deduplicate
(§8), so redelivery is harmless.

### 6.2 Relay Design

- Claims batches of 100 with `SELECT … FOR UPDATE SKIP LOCKED` — multiple
  relay instances share work without coordination.
- Per-row locking during delivery; Kafka I/O happens while holding the row
  lock (deliberate: prevents two relays publishing the same row).
- Exponential backoff per event (1s → cap), 10 attempts, then `POISON`:
  the event is copied to `ledger.events.dlq` and quarantined for human
  review. Poison is never silently dropped.
- Backlog exposed as the `ledgerflow.outbox.backlog` gauge.

### 6.3 Kafka Configuration

- **Acks `all`**, idempotent producer (`enable.idempotence=true`),
  `max.in.flight.requests.per.connection=5` — no lost or duplicated writes
  from the producer.
- **Partition key = aggregate id**: all events for one transaction keep
  partition order.
- **Manual acknowledgment**: offsets commit only after the consumer's
  database work commits. A crash between "applied" and "acknowledged"
  replays an already-recorded event id — a no-op.
- Versioned topics (`ledger.events.v1`, `settlement.events.v1`,
  `reconciliation.events.v1`) and versioned event types
  (`ledger.transaction.posted.v1`). New optional fields are backward
  compatible; breaking changes get a new version and topic.

### 6.4 At-Least-Once, Always

The system assumes at-least-once delivery everywhere and makes duplicates
harmless: consumers deduplicate on `event_id` via the `processed_events`
table, webhooks deduplicate on `provider_event_id`, and projections are a
fold over postings (re-applying is idempotent by dedup, not by arithmetic).

---

## 7. Concurrency Control

Account rows carry a `version` column for **optimistic locking**. A transfer
reads both accounts, validates, writes postings, and updates cached balances;
if another transaction modified either account concurrently, the commit
fails with an optimistic-lock exception and the transfer **retries with
bounded jittered backoff** (4 attempts, 25–50ms × 2^attempt with jitter).
Past the bound, the client gets a clean `409` and retries with the same
idempotency key — which is safe by §5.

Optimistic locking was chosen over pessimistic because transfers on
*different* accounts never contend; only genuinely hot accounts serialize,
and the retry path handles that gracefully. Pessimistic locks are reserved
for targeted check-then-act cases (webhook dedup, outbox row claiming).

Under contention, correctness is verified by three invariants asserted in
tests: per-transaction `debits == credits`, global net-zero across all
postings, and per-account cached balance == postings-derived balance.

---

## 8. Balance Projections and Replayable State

`balance_projections` is a **derived read model**, not truth. The projection
consumer folds each event's postings into per-account balances with an
atomic SQL upsert, recording the event id in `processed_events` in the same
transaction. Because postings are append-only and truth lives in the
`postings` table:

- The projection can be **dropped and rebuilt** from postings at any time
  (tested path).
- A `/accounts/{id}/balances` endpoint compares all three balance sources —
  cached, authoritative (postings sum), and projected — and reports a
  consistency flag.
- Tampering or corruption is detectable and recoverable without data loss.

This is what "replayable state" means: given the event/posting log, every
derived view can be reconstructed deterministically.

---

## 9. Settlement

Settlement models the asynchronous, unreliable reality of moving money
through an external provider. The settlement consumer creates a settlement
row per posted transfer; the **simulator** then behaves according to the
transfer's requested scenario:

| Scenario | Behavior |
|---|---|
| `SUCCESS` | Settles promptly |
| `FAIL` | Fails with a provider error |
| `TIMEOUT` | Never calls back (reconciler flags `LATE_SETTLEMENT`) |
| `DELAYED` | Calls back after a delay |
| `DUPLICATE_CALLBACK` | Delivers the same webhook twice |
| `PROVIDER_OUTAGE` | Simulates provider downtime |

The simulator is explicitly a **deterministic engineering test tool**, not a
claim of bank connectivity (ADR-011). Its value is honest: it lets the
failure-handling paths — timeouts, duplicates, outages — be exercised
deterministically.

Webhooks are authenticated with **HMAC-SHA256** over the raw body, verified
in constant time *before any database access*. Duplicate deliveries hit the
`provider_event_id` dedup under a row lock and return `duplicate` — exactly
one `settlement.completed` event is ever emitted per settlement.

---

## 10. Reconciliation

Reconciliation answers: "does the outside world agree with our ledger?" A
scheduled job (60s default) compares every posted transfer against its
settlement and records mismatches:

- `MISSING_SETTLEMENT` — posted transfer with no settlement
- `AMOUNT_MISMATCH` / `CURRENCY_MISMATCH` — provider disagrees with the ledger
- `LATE_SETTLEMENT` — stuck in PENDING/PROCESSING past the threshold (120s)
- `DUPLICATE_SETTLEMENT` / `UNEXPECTED_SETTLEMENT` — structurally guarded,
  checked as corruption probes

Guarantees: runs are idempotent (an open mismatch is reported once; reruns
don't duplicate); **mismatches are never auto-resolved** — they stay open
until a human with `ADMIN` resolves them, and resolution is written to the
audit log; the reconciler never writes to ledger or settlement tables, it
only observes and records. Every new mismatch emits
`reconciliation.mismatch.detected.v1`.

---

## 11. Exchange Rates: Real Data, Honest Labeling

FX rates come from the **European Central Bank's daily reference feed**
(`eurofxref-daily.xml`) — real data, ingested with full provenance:
provider, base/quote pair, the rate's effective date, and fetch time. The
XML parser is hardened (no external entities, no DTD). There is **no
fabricated fallback rate**: if the feed is unreachable and no cached rate
exists, conversion fails loudly with `STALE_RATE` rather than inventing a
number. Cross-rates convert through EUR with `HALF_EVEN` rounding to target
minor units. FX conversion is currently quote-only; transfers are
single-currency.

---

## 12. Security Architecture

- **Authentication:** stateless JWT (HS256), roles in the `roles` claim. A
  development token endpoint exists for demos and **must be disabled** in
  shared environments (`ledgerflow.auth.dev-token-endpoint=false`); the
  `JwtService` is the single swap point for a real OIDC provider.
- **Authorization:** method-level RBAC — `ADMIN` (everything, incl. mismatch
  resolution), `OPERATOR` (accounts, transfers, settlements, reconciliation),
  `AUDITOR` (read-only), `SERVICE` (machine transfers).
- **Webhooks** use HMAC, not JWT — mirroring real provider integrations.
- **Rate limiting:** token bucket per principal (IP fallback) in Redis via
  an atomic Lua script. Webhooks are exempt (own auth). If Redis is down,
  the filter **fails open** — rate limiting must never block money movement.
- **Transport/input:** bean validation on all DTOs, RFC 7807 errors,
  security headers (`Content-Security-Policy: default-src 'none'`,
  `X-Frame-Options: DENY`).
- **Audit:** security-sensitive operations (freezes, reversals, mismatch
  resolutions, projection rebuilds) go to the append-only `audit_log`.

---

## 13. Observability

- **Structured JSON logs** with correlation IDs propagated across HTTP and
  Kafka boundaries.
- **Metrics (Prometheus):** `ledgerflow.transfer.duration` (p50/p95/p99),
  `ledgerflow.outbox.backlog`, open mismatches, HTTP latencies, JVM.
- **Tracing:** OpenTelemetry OTLP export; Jaeger in the Compose stack.
- **Health:** liveness/readiness probes shaped for Kubernetes.
- **Dashboards:** a provisioned Grafana "LedgerFlow Overview" dashboard.

---

## 14. Failure Model

| Failure | Client impact | Recovery | Duplicates? |
|---|---|---|---|
| Kafka down | None — transfers still commit locally | Relay backoff; backlog drains | Possible on recovery; consumers dedup |
| PostgreSQL restart | 5xx until DB returns | Client retries same key → replay or fresh execute | No |
| Relay crash | Publishing pauses | Next relay republishes unacked rows | Yes; deduped |
| Consumer killed mid-processing | Offset unacked | Rebalance redelivers | Redelivery is a no-op |
| Provider timeout | None (settlement is async) | Reconciler flags `LATE_SETTLEMENT` | N/A |
| Duplicate webhook | None | `provider_event_id` dedup under row lock | No |
| Projection corruption | Stale reads | Rebuild from postings | N/A |

---

## 15. Testing Strategy

| Layer | What's covered |
|---|---|
| Unit (32 tests) | Money math, invariant validation, idempotency protocol, transfer orchestration, reconciler logic, webhook HMAC, ECB parsing |
| Integration | Concurrency torture (400 parallel transfers asserting global `debits == credits`), idempotency races, outbox relay, projection rebuild, webhook dedup, reconciliation |
| Load (k6) | Transfer soak, hot-account contention, idempotency replay — with teardown assertions that fail the run if cached balances diverge from postings |

Invariants asserted everywhere: per-transaction balance, global net-zero,
cached == authoritative per account, conservation of total money.

---

## 16. Performance and Scaling

Benchmarks are **executed, not estimated**: k6 scripts run against the real
stack and write summaries to `load-tests/k6/results/`. Known contention
points: hot accounts (bounded optimistic-lock retries, then clean 409),
single-threaded relay polling (safe to parallelize via `SKIP LOCKED`),
per-partition consumer throughput.

Scaling story: correct single-node operation today; 10k users via app
replicas + more Kafka partitions + read replicas; 1M users via Postgres
partitioning, hot-account sharding, and Kubernetes (justified only there —
the probes are already shaped for it). Scale changes topology, never the
correctness invariants.

---

## 17. Key Decisions (ADRs)

| # | Decision | Rationale |
|---|---|---|
| 1 | PostgreSQL as the authority | ACID + rich constraints; Redis never holds money |
| 2 | Double-entry, minor-unit BIGINT | Structural balance; no float drift |
| 3 | Transactional outbox | No dual-write loss |
| 4 | At-least-once + idempotent consumers | Duplicates expected and harmless |
| 5 | Balances as projections | Postings are truth; read models rebuild |
| 6 | Kafka key = aggregate id | Per-aggregate ordering |
| 7 | Optimistic locking | Contention only where it exists |
| 8 | Redis for rate limiting only | Narrow, non-authoritative use |
| 9 | Modular monolith | One deployable, microservice-ready seams |
| 10 | Kubernetes only at real scale | Don't pay the complexity tax early |
| 11 | Settlement simulator | Honest failure testing, no fake banking claims |

---

## 18. Limitations and Future Work

**Limitations:** settlement is simulated; transfers are single-currency; the
dev token endpoint must be disabled outside demos; single-primary ledger (no
multi-region story).

**Future work:** cross-currency transfers as multi-leg FX postings using ECB
rates; Debezium CDC replacing the polling relay at high throughput; real
OIDC integration; inbound provider-initiated settlement webhooks.

---

## 19. Conclusion

LedgerFlow demonstrates that the hard parts of moving money — retries,
concurrency, async settlement, and auditability — are solvable with a small
set of patterns applied rigorously: idempotency keys at the edge,
double-entry invariants in the domain, a transactional outbox between the
database and the event bus, idempotent consumers downstream, and an
independent reconciler watching everything. Each pattern covers the failure
modes the others leave open, and the system's own test suite proves the
invariants hold under contention. The result is a codebase that a payments
team would recognize — not because it pretends to be a bank, but because it
takes the same failures seriously.

---

## Appendix A: API Summary

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/auth/token` | none (dev) | Issue JWT |
| POST | `/api/v1/accounts` | OPERATOR+ | Create account |
| GET | `/api/v1/accounts/{id}` | any | Get account |
| GET | `/api/v1/accounts/{id}/balances` | any | Compare 3 balance sources |
| POST | `/api/v1/transfers` | OPERATOR+ | Idempotent transfer |
| GET | `/api/v1/transfers/{id}` | any | Transfer status |
| POST | `/api/v1/settlements/webhook` | HMAC | Provider callback |
| POST | `/api/v1/reconciliation/runs` | OPERATOR+ | Trigger run |
| GET | `/api/v1/reconciliation/mismatches` | any | List mismatches |
| POST | `/api/v1/reconciliation/mismatches/{id}/resolve` | ADMIN | Resolve (audited) |
| POST | `/api/v1/fx/refresh` | OPERATOR+ | Refresh ECB rates |
| GET | `/api/v1/fx/quote` | any | Conversion quote |

## Appendix B: Repository Layout

```
src/main/java/com/ledgerflow/  # ledger, transfer, idempotency, outbox, events,
                               # projection, settlement, reconciliation, fx,
                               # security, api, common
src/main/resources/db/migration/  # V1-V7 Flyway migrations
src/test/                         # unit + integration tests
load-tests/k6/                    # soak, hot-account, idempotency scripts
observability/                    # prometheus.yml, Grafana provisioning + dashboard
docs/                             # architecture, ADRs, runbook, ...
.github/workflows/                # CI: build, test, Trivy, Docker
docker-compose.yml                # Postgres 16, Redpanda, Redis, app,
                                  # Prometheus, Grafana, Jaeger
```
