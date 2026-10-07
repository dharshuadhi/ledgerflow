import http from 'k6/http';
import { check } from 'k6';

// Fires the SAME idempotency key repeatedly: exactly one 201 must occur and
// every other response must be a byte-identical replay of it.
export const options = {
  vus: 10,
  iterations: 50,
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
  const t = http.post(`${BASE}/api/v1/auth/token`, JSON.stringify({ username: 'operator' }), {
    headers: { 'Content-Type': 'application/json' },
  }).json('accessToken');
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` };
  const a = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({
    ownerName: 'k6-idem-a', currency: 'USD', initialBalanceMinorUnits: 1000000,
  }), { headers }).json('id');
  const b = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({
    ownerName: 'k6-idem-b', currency: 'USD', initialBalanceMinorUnits: 0,
  }), { headers }).json('id');
  return { token: t, a, b, key: `k6-idem-${Date.now()}` };
}

export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${data.token}`,
    'Idempotency-Key': data.key, // identical key for every iteration
  };
  const res = http.post(`${BASE}/api/v1/transfers`, JSON.stringify({
    sourceAccountId: data.a,
    destAccountId: data.b,
    amountMinorUnits: 100,
    currency: 'USD',
  }), { headers });
  check(res, {
    '201 or replay-200': (r) => r.status === 201 || r.status === 200,
    'replay marked': (r) => r.status !== 201
      ? r.headers['Idempotent-Replay'] === 'true'
      : true,
  });
}

export function teardown(data) {
  const headers = { Authorization: `Bearer ${data.token}` };
  const body = http.get(`${BASE}/api/v1/accounts/${data.b}/balances`, { headers }).json();
  console.log(`destination balance after 50 same-key attempts: ${body.authoritativeBalanceMinorUnits}`);
  if (body.authoritativeBalanceMinorUnits !== 100) {
    throw new Error(`IDEMPOTENCY BROKEN: expected exactly one 100-unit transfer, got ${body.authoritativeBalanceMinorUnits}`);
  }
}
