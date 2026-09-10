import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const initialResponses = new Counter('initial_responses');
const replayedResponses = new Counter('replayed_responses');
const mismatchResponses = new Counter('mismatch_responses');

export const options = {
  scenarios: {
    race_condition: {
      executor: 'per-vu-iterations',
      vus: 50,
      iterations: 1,
      maxDuration: '30s',
    },
  },
};

export default function () {
  const url = __ENV.TARGET_URL || 'http://localhost:8080/api/v1/orders';
  const payload = JSON.stringify({
    orderId: 'benchmark-order-999',
    amount: 149.99,
    currency: 'USD',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': 'benchmark-concurrent-key-001',
      'X-API-Key': 'tenant-test-benchmark',
    },
  };

  const res = http.post(url, payload, params);

  const isSuccess = check(res, {
    'status is 200 or 201': (r) => r.status === 200 || r.status === 201,
    'has Idempotent-Replayed header': (r) => r.headers['Idempotent-Replayed'] !== undefined,
  });

  if (isSuccess) {
    const isReplayed = res.headers['Idempotent-Replayed'] === 'true';
    if (isReplayed) {
      replayedResponses.add(1);
    } else {
      initialResponses.add(1);
    }
  } else if (res.status === 422) {
    mismatchResponses.add(1);
  }
}
