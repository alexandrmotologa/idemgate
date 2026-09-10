import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const acceptedRequests = new Counter('rate_limit_accepted');
const throttledRequests = new Counter('rate_limit_throttled');

export const options = {
  scenarios: {
    burst_traffic: {
      executor: 'constant-arrival-rate',
      rate: 100, // 100 requests per second
      timeUnit: '1s',
      duration: '5s',
      preAllocatedVUs: 20,
      maxVUs: 50,
    },
  },
};

export default function () {
  const url = __ENV.TARGET_URL || 'http://localhost:8080/api/v1/orders';
  const payload = JSON.stringify({ ping: 'rate-limit-check' });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': 'rate-limit-tenant-tester',
    },
  };

  const res = http.post(url, payload, params);

  check(res, {
    'status is 200/201 or 429': (r) => r.status === 200 || r.status === 201 || r.status === 429,
  });

  if (res.status === 429) {
    throttledRequests.add(1);
    check(res, {
      'has Retry-After header': (r) => r.headers['Retry-After'] !== undefined,
    });
  } else {
    acceptedRequests.add(1);
  }
}
