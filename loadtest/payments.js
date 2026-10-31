// Creates payments at a fixed arrival rate and polls each one once, like a client would.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://payment-service:8080';
const RATE_PER_SECOND = Number(__ENV.RATE || 200); // 200/s = 12,000 payments/min
const DURATION = __ENV.DURATION || '2m';
// Lets the JIT and connection pools warm up; thresholds only judge the measured scenario.
const WARMUP_DURATION = '20s';
const WARMUP_RATE_PER_SECOND = 50;
const P95_BUDGET_MS = 150;
const MAX_AMOUNT_CENTS = 100000;
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      rate: WARMUP_RATE_PER_SECOND,
      timeUnit: '1s',
      duration: WARMUP_DURATION,
      preAllocatedVUs: 50,
    },
    payments: {
      executor: 'constant-arrival-rate',
      startTime: WARMUP_DURATION,
      rate: RATE_PER_SECOND,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 300,
      maxVUs: 600,
    },
  },
  thresholds: {
    'http_req_duration{scenario:payments,name:create}': [`p(95)<${P95_BUDGET_MS}`],
    'http_req_duration{scenario:payments,name:poll}': [`p(95)<${P95_BUDGET_MS}`],
    'checks{scenario:payments}': ['rate>0.999'],
    'dropped_iterations{scenario:payments}': ['count==0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const amount = ((Math.floor(Math.random() * MAX_AMOUNT_CENTS) + 100) / 100).toFixed(2);
  const created = http.post(
    `${BASE_URL}/payments`,
    JSON.stringify({ customerId: `customer-${__VU}`, amount, currency: 'USD' }),
    { headers: JSON_HEADERS, tags: { name: 'create' } },
  );
  check(created, { 'payment accepted': (r) => r.status === 202 });
  if (created.status !== 202) {
    return;
  }
  const polled = http.get(`${BASE_URL}/payments/${created.json('paymentId')}`, { tags: { name: 'poll' } });
  check(polled, { 'payment readable': (r) => r.status === 200 });
}
