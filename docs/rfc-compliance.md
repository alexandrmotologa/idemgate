# IETF RFC Compliance Guide

IdemGate follows the specifications in IETF draft `draft-ietf-httpapi-idempotency-key-header-04` (The Idempotency-Key HTTP Header Field) and RFC 7807 (Problem Details for HTTP APIs).

## Supported Specifications

- **IETF Draft**: `draft-ietf-httpapi-idempotency-key-header-04`
- **Error Format**: RFC 7807 (`application/problem+json`)

## Key Header Semantics

Clients supply the header in HTTP requests:

```http
POST /api/v1/charges HTTP/1.1
Host: api.example.com
Idempotency-Key: 7b2b8d0c-6072-4d64-9a1b-90f772592dc9
Content-Type: application/json

{"amount": 1000, "currency": "usd"}
```

### Constraints on the Header Value

Per section 2.1 of the draft:
- The `Idempotency-Key` value is an opaque string containing only ASCII printable characters (`VCHAR`: 0x21 to 0x7E).
- Control characters and spaces are rejected with `400 Bad Request`.
- Maximum key length is configurable in IdemGate (default: 256 characters). Keys exceeding this length return `400 Bad Request`.

### Safe HTTP Methods Handling

Per Section 2.1:
- The `Idempotency-Key` request header has no semantics for safe HTTP methods (`GET`, `HEAD`, `OPTIONS`, `TRACE`).
- Safe requests containing an `Idempotency-Key` are passed directly through to the upstream microservice without acquiring locks or creating cache records.

## Digest Validation and Replay

When a request contains an `Idempotency-Key`, IdemGate computes a cryptographic fingerprint of the request components.

### 1. Fresh Request
- Upstream is invoked once.
- The response is returned to the caller with header:
  ```http
  Idempotent-Replayed: false
  ```

### 2. Matching Replay
- When the same key is supplied with an identical digest, IdemGate retrieves the stored response and returns it immediately:
  ```http
  HTTP/1.1 200 OK
  Idempotent-Replayed: true
  Original-Date: Thu, 10 Sep 2026 10:00:00 GMT
  ```
- Upstream service is not contacted.

### 3. Payload Mismatch Error
- If a client repeats an existing `Idempotency-Key` but alters the method, path, query parameters, payload body, or whitelisted header, IdemGate rejects the request with status code `422 Unprocessable Entity`:
  ```http
  HTTP/1.1 422 Unprocessable Entity
  Content-Type: application/problem+json

  {
    "type": "https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-04#section-2.7",
    "title": "Idempotency Key Payload Mismatch",
    "status": 422,
    "detail": "The provided Idempotency-Key was previously used with a different request payload.",
    "instance": "/api/v1/charges"
  }
  ```

### 4. Transient 5xx Upstream Server Errors
- Per Section 2.6: Transient server errors (500, 502, 503, 504) returned by the upstream backend are **not** permanently cached as resolved idempotency records.
- IdemGate immediately releases the in-flight lock, returning the 5xx response to the client with `Idempotent-Replayed: false` so subsequent retries are allowed to hit the upstream again once recovered.

### 5. Concurrent In-Flight Execution
- If a duplicate request arrives while the original request is still being processed upstream, IdemGate holds the connection until the initial request finishes.
- Once the original request finishes, both the original caller and the waiting duplicate caller receive identical responses. The waiting caller receives `Idempotent-Replayed: true`.
- If the wait duration exceeds `idemgate.idempotency.lock-ttl-seconds`, IdemGate returns `504 Gateway Timeout`.
