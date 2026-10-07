# Runbook

## Start everything

```bash
cp .env.example .env   # fill in secrets
docker compose up --build
```

- API: http://localhost:8080
- Swagger: http://localhost:8080/swagger-ui.html
- Grafana: http://localhost:3000 (admin/admin)
- Prometheus: http://localhost:9090
- Jaeger: http://localhost:16686

## Get a token

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"operator"}' | jq -r .accessToken)
```

## Move money

```bash
A=$(curl -s -X POST localhost:8080/api/v1/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"ownerName":"alice","currency":"USD","initialBalanceMinorUnits":10000}' | jq -r .id)
B=$(curl -s -X POST localhost:8080/api/v1/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"ownerName":"bob","currency":"USD","initialBalanceMinorUnits":0}' | jq -r .id)

curl -s -X POST localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"sourceAccountId\":\"$A\",\"destAccountId\":\"$B\",\"amountMinorUnits\":2500,\"currency\":\"USD\"}" | jq .
```

## Simulate a failure

Add `"settlementScenario": "TIMEOUT"` (or `FAIL`, `DELAYED`,
`DUPLICATE_CALLBACK`, `PROVIDER_OUTAGE`) to the transfer body, then:

```bash
curl -s -X POST localhost:8080/api/v1/reconciliation/runs \
  -H "Authorization: Bearer $TOKEN" | jq .
curl -s localhost:8080/api/v1/reconciliation/mismatches \
  -H "Authorization: Bearer $TOKEN" | jq .
```

## Compare balance sources

```bash
curl -s localhost:8080/api/v1/accounts/$A/balances \
  -H "Authorization: Bearer $TOKEN" | jq .
# cachedBalanceMinorUnits vs authoritativeBalanceMinorUnits vs projectedBalanceMinorUnits
```

## Outbox backlog

```bash
curl -s localhost:8080/actuator/prometheus \
  | grep ledgerflow_outbox_backlog
```

## Common issues

| Symptom | Likely cause | Fix |
|---|---|---|
| 401 on API calls | missing/expired token | re-issue via `/auth/token` |
| 409 on transfer | same key in flight | wait, then retry same key |
| 422 key reuse | key used with different body | generate a new key per operation |
| Relay not publishing | Redpanda down | `docker compose ps`; check `ledgerflow.outbox.backlog` |
| Balances differ | projection lag | wait for consumer; rebuild if truly stuck |
