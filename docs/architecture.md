# IdemGate Architecture Guide

This document describes the internal structure, concurrency lifecycle, and storage models used by IdemGate.

## Overview

IdemGate acts as a reverse proxy in front of HTTP microservices. When a request arrives, the proxy checks for an `Idempotency-Key` header. If present, IdemGate intercepts the request, runs concurrency coordination, and caches the result. If no key is present, IdemGate evaluates rate limits and forwards the request directly to the upstream service.

```
+-----------------------------------------------------------------------------------+
|                                     IdemGate                                      |
|                                                                                   |
|  [Incoming HTTP Request]                                                          |
|            |                                                                      |
|            v                                                                      |
|  [TenantContextResolver] ----> [RateLimiterService] (Token Bucket)                |
|                                         |                                         |
|                                         v (Passed)                                |
|  [RequestFingerprinter] (SHA-256 Digest: Method + Path + Query + Body)            |
|            |                                                                      |
|            v                                                                      |
|  [IdempotencyEngine] <------> [IdempotencyStore SPI]                              |
|            |                      |                      |                        |
|            |                      v                      v                        |
|            |             [RedisStore (Redisson)] [InMemoryStore (Caffeine)]       |
|            |                                                                      |
|      +-----+-----------------------------+                                        |
|      |                                   |                                        |
|      v (First Request: IN_FLIGHT)        v (Concurrent Duplicate: IN_FLIGHT)      |
|  [UpstreamDispatcher]               [Suspend Caller on Notification Channel]      |
|      |                                   |                                        |
|      v (Upstream response)               |                                        |
|  [Save Response as RESOLVED]             |                                        |
|  [Publish Resolution Event] ------------>| (Wakes up and retrieves cached result) |
|      |                                   |                                        |
|      v                                   v                                        |
|  [Return 201 Replayed: false]        [Return 201 Replayed: true]                  |
+-----------------------------------------------------------------------------------+
```

## Concurrency Lifecycle and Locking

IdemGate coordinates concurrent requests using a two-phase protocol:

1. **State Check and Lock Acquisition**:
   The proxy checks the storage backend for an existing record matching the provided `Idempotency-Key`.
   - If the key is not present, the proxy atomically acquires an in-flight lock with a configurable lease duration (default 30 seconds) and marks the record state as `IN_FLIGHT`. The request proceeds to upstream forwarding.
   - If the key exists and its state is `IN_FLIGHT`, a concurrent request is already being processed. The incoming request does not hit the backend. Instead, it subscribes to an internal notification channel (`idem:resolved:{key}`) and suspends execution until the first request completes or a timeout occurs.
   - If the key exists and its state is `RESOLVED`, the proxy compares the stored cryptographic digest with the current request digest. If they match, the cached response is returned immediately with the header `Idempotent-Replayed: true`. If they do not match, the proxy aborts the request and returns `422 Unprocessable Entity`.

2. **Resolution and Notification**:
   Once the upstream service finishes processing the first request, IdemGate stores the HTTP status code, response headers, and response body in the cache with the record state `RESOLVED`. IdemGate then publishes a resolution event on the notification channel. Suspended duplicate callers wake up, retrieve the cached response, and return it with `Idempotent-Replayed: true`.

3. **Timeout and Failure Handling**:
   If the upstream service fails with an error (for example, connection reset or read timeout), the in-flight lock is removed so subsequent attempts can retry cleanly. If a waiting concurrent request exceeds its wait timeout (default 30 seconds), IdemGate returns `504 Gateway Timeout`.

## Dual Backplane Storage SPI

To support both enterprise distributed deployments and lightweight local testing, IdemGate provides a pluggable store interface: `IdempotencyStore`.

### 1. Redis Backplane (`RedisIdempotencyStore`)

Used in multi-node clusters where multiple IdemGate proxy instances share state:
- **Locking**: Redisson distributed reentrant locks with lease renewal.
- **Storage**: Redis hashes for storing metadata, digest, and response payload with TTL expiration (default 24 hours).
- **Notifications**: Redis Pub/Sub topic `idem:resolved:{key}` used to wake up duplicate requests waiting across different proxy pods.

### 2. In-Memory Backplane (`InMemoryIdempotencyStore`)

Used for standalone deployments, developer workstations, and automated integration tests:
- **L1 Cache**: High-performance Caffeine cache for fast lookups.
- **Concurrency**: Java 21 `ConcurrentHashMap` with `CompletableFuture<CachedHttpResponse>` used as non-blocking notification handles.
- Zero dependencies on external infrastructure.

## Request Fingerprinting

Per Section 2.7 of the IETF draft, an idempotency key must not be reused across requests with different parameters or payloads. IdemGate generates a deterministic fingerprint using SHA-256 over:
- HTTP Request Method (e.g. `POST`)
- Normalized URI Path (e.g. `/api/v1/payments`)
- Sorted Query Parameters
- Raw Request Body Bytes

If two requests arrive with the same key but different payloads, IdemGate returns an RFC 7807 `ProblemDetail` payload with status `422 Unprocessable Entity`.

## Multi-Tenant Rate Limiting

IdemGate includes a distributed Token Bucket rate limiter:
- **Tenant Resolver**: Extracts the tenant identifier from request attributes in order of precedence:
  1. Configurable header (e.g., `X-API-Key` or `X-Tenant-ID`)
  2. Authorization header bearer token hash
  3. Client remote IP address
- **Token Bucket Engine**:
  - Redis backplane: Executes an atomic Lua script (`token_bucket.lua`) that computes available tokens based on timestamp difference, subtracts consumed tokens, and updates bucket state in a single round trip.
  - In-memory backplane: Uses an atomic, thread-safe local bucket counter.
- **Response Headers**:
  - `X-RateLimit-Limit`: Maximum burst capacity.
  - `X-RateLimit-Remaining`: Available tokens in the current window.
  - `X-RateLimit-Reset`: Seconds remaining until full replenishment.
  - `Retry-After`: Included when status is `429 Too Many Requests`.
