import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.DURATION || '5m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1500'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

function token() {
  const res = http.post(`${BASE}/api/v1/auth/token`, JSON.stringify({ username: 'operator' }), {
    headers: { 'Content-Type': 'application/json' },
  });
  return res.json('accessToken');
}

export function setup() {
  const t = token();
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` };
  const mk = (owner, balance) =>
    http.post(`${BASE}/api/v1/accounts`, JSON.stringify({
      ownerName: owner, currency: 'USD', initialBalanceMinorUnits: balance,
    }), { headers }).json('id');
  // Seed once; all VUs transfer between these accounts.
  return { token: t, accounts: [mk('k6-a', 100000000), mk('k6-b', 100000000), mk('k6-c', 100000000)] };
}

export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${data.token}`,
    'Idempotency-Key': uuidv4(), // every iteration is a distinct transfer
  };
  const accs = data.accounts;
  const from = accs[Math.floor(Math.random() * accs.length)];
  let to = accs[Math.floor(Math.random() * accs.length)];
  if (to === from) to = accs[(accs.indexOf(from) + 1) % accs.length];

  const res = http.post(`${BASE}/api/v1/transfers`, JSON.stringify({
    sourceAccountId: from,
    destAccountId: to,
    amountMinorUnits: 100 + Math.floor(Math.random() * 900),
    currency: 'USD',
    description: 'k6 soak',
  }), { headers });

  check(res, {
    'created': (r) => r.status === 201,
    'not a replay': (r) => r.headers['Idempotent-Replay'] === 'false',
  });
  sleep(0.1);
}

export function handleSummary(data) {
  return {
    'load-tests/k6/results/soak-summary.json': JSON.stringify({
      timestamp: new Date().toISOString(),
      vus: options.scenarios.soak.vus,
      http_req_duration_p95: data.metrics.http_req_duration.values['p(95)'],
      http_req_duration_p99: data.metrics.http_req_duration.values['p(99)'],
      http_req_failed_rate: data.metrics.http_req_failed.values.rate,
      iterations: data.metrics.iterations.values.count,
      note: 'Results from actual execution; do not hand-edit. See docs/performance.md for methodology.',
    }, null, 2),
  };
}
