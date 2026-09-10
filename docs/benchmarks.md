# IdemGate Benchmarks and Verification

This document covers running concurrency and rate limiting tests against IdemGate using k6.

## Test Scripts

Benchmarks are located in the `benchmarks/` directory:

- `benchmarks/idempotency-race-test.js`: Generates 100 concurrent requests with the identical `Idempotency-Key` directed to an upstream endpoint configured with artificial delay. Verifies that the upstream service processes the request exactly once and that all callers receive status 200 or 201 with identical response bodies.
- `benchmarks/rate-limit-burst-test.js`: Verifies token bucket exhaustion and replenishment mechanics under burst loads.

## Running Tests with k6

### 1. Install k6

Ensure k6 is installed on your system:

```bash
# Windows (winget or choco)
winget install k6 --source winget
```

### 2. Start IdemGate

Start IdemGate with the upstream mock enabled:

```bash
java -jar target/idemgate-1.0.0-SNAPSHOT.jar
```

### 3. Run Concurrency Race Test

```bash
k6 run benchmarks/idempotency-race-test.js
```

Expected output metrics:
- Upstream requests: exactly 1
- Downstream responses: 100
- Initial response: `Idempotent-Replayed: false` (1)
- Replayed responses: `Idempotent-Replayed: true` (99)
- Failed requests: 0

### 4. Run Rate Limit Burst Test

```bash
k6 run benchmarks/rate-limit-burst-test.js
```

Expected output:
- Requests within capacity: HTTP 200
- Requests exceeding capacity: HTTP 429 with `Retry-After` header
- Requests after refill window: HTTP 200
