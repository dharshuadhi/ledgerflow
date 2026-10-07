import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// Every VU drains the SAME source account: exercises optimistic-lock retries
// on a hot account. Success criterion: zero failed requests and the final
// balance equals initial minus (iterations * amount).
export const options = {
  scenarios: {
    hot: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 30),
      duration: __ENV.DURATION || '3m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const AMOUNT = 50;

export function setup() {
  const t = http.post(`${BASE}/api/v1/auth/token`, JSON.stringify({ username: 'operator' }), {
    headers: { 'Content-Type': 'application/json' },
  }).json('accessToken');
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` };
  const hot = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({
    ownerName: 'k6-hot', currency: 'USD', initialBalanceMinorUnits: 1000000000,
  }), { headers }).json('id');
  const sinks = [0, 1, 2].map((i) =>
    http.post(`${BASE}/api/v1/accounts`, JSON.stringify({
      ownerName: `k6-sink-${i}`, currency: 'USD', initialBalanceMinorUnits: 0,
    }), { headers }).json('id'));
  return { token: t, hot, sinks, initialHot: 1000000000 };
}

export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${data.token}`,
    'Idempotency-Key': uuidv4(),
  };
  const res = http.post(`${BASE}/api/v1/transfers`, JSON.stringify({
    sourceAccountId: data.hot,
    destAccountId: data.sinks[__VU % data.sinks.length],
    amountMinorUnits: AMOUNT,
    currency: 'USD',
    description: 'k6 hot account',
  }), { headers });
  check(res, { 'created': (r) => r.status === 201 });
  sleep(0.05);
}

export function teardown(data) {
  const headers = { Authorization: `Bearer ${data.token}` };
  const res = http.get(`${BASE}/api/v1/accounts/${data.hot}/balances`, { headers });
  const body = res.json();
  console.log(`hot account final: cached=${body.cachedBalanceMinorUnits} ` +
    `authoritative=${body.authoritativeBalanceMinorUnits} ` +
    `consistent=${body.cacheConsistent}`);
  if (!body.cacheConsistent) {
    throw new Error('CACHE DIVERGED FROM POSTINGS UNDER CONTENTION');
  }
}
