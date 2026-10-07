# ADR-009: Modular Monolith Over Microservices

**Status:** Accepted

## Context

The system has natural module boundaries (ledger, settlement, reconciliation,
projection). The question is whether they should be separate deployables.

## Decision

**One deployable** with strict package boundaries (`com.ledgerflow.ledger`,
`.settlement`, `.reconciliation`, `.projection`, …). Modules communicate via
the database (outbox) and Kafka (events) — the same seams microservices would
use — so extraction later is mechanical, not architectural.

## Alternatives

- **Microservices now:** would multiply operational surface (service
  discovery, distributed config, per-service pipelines) with zero scaling
  need. The failure modes we'd "learn" are mostly self-inflicted. Rejected.

## Consequences

- Simple to run (`docker compose up`), simple to reason about transactions.
- The outbox relay and Kafka consumers already run as independent logical
  units; if settlement load ever justifies it, that consumer group becomes
  its own service without contract changes.
