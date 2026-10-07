# Security

## Authentication

- **JWT bearer tokens** (HS256). Roles are carried in the `roles` claim.
- Demo mode: `POST /api/v1/auth/token` issues tokens for demo users.
  **Disable in any shared environment** (`ledgerflow.auth.dev-token-endpoint=false`)
  and point at a real OIDC provider — `JwtService` is the single swap point.
- JWT secret comes from `LEDGERFLOW_JWT_SECRET`; the default is dev-only.

## Authorization (RBAC)

| Role | Capabilities |
|---|---|
| `ADMIN` | everything, incl. resolving mismatches |
| `OPERATOR` | accounts, transfers, settlements, reconciliation runs, FX refresh |
| `AUDITOR` | read-only |
| `SERVICE` | machine transfers |

Enforced with method security (`@PreAuthorize`) on controllers.

## Webhook authentication

The settlement webhook does **not** use JWT. Its credential is the
`X-Signature` HMAC-SHA256 of the raw body, verified in constant time
*before any database access*. This mirrors how Stripe-style providers work.

## Rate limiting

Token-bucket per principal (fallback: per IP), backed by Redis via an atomic
Lua script. Webhooks are exempt (they carry their own auth). If Redis is down
the filter **fails open** — rate limiting must never block money movement.

## Input & transport

- Bean Validation on all DTOs; RFC 7807 problem responses.
- Security headers: `Content-Security-Policy: default-src 'none'`,
  `X-Frame-Options: DENY`.
- Amounts are validated positive; currencies are ISO 4217.

## Secrets

- No secrets in the repo. `.env.example` documents every variable.
- CI runs Trivy on the repo and the built image.

## Audit

Security-sensitive operations (mismatch resolution, projection rebuilds,
account freezes) are written to the append-only `audit_log`.
