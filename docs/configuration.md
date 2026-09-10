# IdemGate Configuration Reference

IdemGate can be configured through `application.yml` or standard environment variables.

## Configuration Structure

```yaml
server:
  port: 8080

idemgate:
  upstream:
    url: "http://localhost:8080/upstream-mock"
    connect-timeout-ms: 5000
    read-timeout-ms: 30000

  storage:
    type: "memory" # Options: memory, redis

  idempotency:
    enabled: true
    header-name: "Idempotency-Key"
    lock-ttl-seconds: 30
    record-ttl-seconds: 86400
    max-body-size-bytes: 10485760 # 10 MB limit
    routes:
      - path: "/api/**"
        required: false

  rate-limit:
    enabled: true
    tenant-header: "X-API-Key"
    default-capacity: 100
    default-refill-rate: 20
    tiers:
      free:
        capacity: 20
        refill-rate: 5
      pro:
        capacity: 200
        refill-rate: 50
      enterprise:
        capacity: 1000
        refill-rate: 250
```

## Environment Variables

| Variable | Description | Default |
|---|---|---|
| `SERVER_PORT` | HTTP listening port | `8080` |
| `IDEMGATE_UPSTREAM_URL` | Destination service URL | `http://localhost:8080/upstream-mock` |
| `IDEMGATE_STORAGE_TYPE` | Storage backend (`memory` or `redis`) | `memory` |
| `SPRING_DATA_REDIS_HOST` | Redis hostname | `localhost` |
| `SPRING_DATA_REDIS_PORT` | Redis port | `6379` |
| `SPRING_DATA_REDIS_PASSWORD`| Redis password | empty |
| `IDEMGATE_LOCK_TTL` | Concurrency lock TTL in seconds | `30` |
| `IDEMGATE_RECORD_TTL` | Resolved idempotency cache TTL in seconds | `86400` |
| `IDEMGATE_RATE_LIMIT_ENABLED`| Enable rate limiting | `true` |
| `IDEMGATE_RATE_LIMIT_CAPACITY`| Default token bucket burst capacity | `100` |
| `IDEMGATE_RATE_LIMIT_REFILL_RATE`| Default tokens replenished per second | `20` |

## Spring Profiles

- **default**: Runs with embedded In-Memory storage (Caffeine + ConcurrentHashMap) and embedded upstream mock.
- **redis**: Connects to an external Redis 7+ instance for distributed locks and shared cache.
- **docker**: Configured for Docker Compose environments with service networking.
