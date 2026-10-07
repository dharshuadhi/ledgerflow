# ADR-010: When Kubernetes Becomes Justified

**Status:** Accepted

## Context

It's tempting to ship Kubernetes manifests on day one. It would be theater:
one stateful database, one app, no scaling need.

## Decision

No Kubernetes manifests in this repository. The deployment story is:

1. `docker compose up` — local development and demos.
2. Single VM / container host — early production shape.
3. **Kubernetes when justified:** multiple app replicas for availability,
   independent scaling of the outbox relay and consumer groups, and secret
   management via an external secrets operator.

Explicit triggers for step 3: sustained CPU saturation on one replica,
need for zero-downtime deploys beyond compose, or multi-AZ requirements.

## Consequences

- The repo stays honest about its scale (see docs/scaling.md).
- Health endpoints (`/actuator/health/liveness`, `/readiness`) are already
  Kubernetes-shaped, so the migration is configuration, not code.
