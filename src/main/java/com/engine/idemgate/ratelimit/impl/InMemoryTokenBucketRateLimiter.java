package com.engine.idemgate.ratelimit.impl;

import com.engine.idemgate.model.RateLimitResult;
import com.engine.idemgate.ratelimit.RateLimiterBackend;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory token bucket rate limiter for single-node deployments and tests.
 */
@Component
@ConditionalOnProperty(name = "idemgate.storage.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryTokenBucketRateLimiter implements RateLimiterBackend {

    private final ConcurrentHashMap<String, BucketState> buckets = new ConcurrentHashMap<>();

    @Override
    public Mono<RateLimitResult> tryAcquire(String tenantId, long capacity, long refillRate, long cost) {
        BucketState bucket = buckets.computeIfAbsent(tenantId, id -> new BucketState(capacity));
        RateLimitResult result = bucket.tryAcquire(capacity, refillRate, cost);
        return Mono.just(result);
    }

    private static class BucketState {
        private double tokens;
        private long lastUpdatedMs;

        public BucketState(long initialCapacity) {
            this.tokens = initialCapacity;
            this.lastUpdatedMs = System.currentTimeMillis();
        }

        public synchronized RateLimitResult tryAcquire(long capacity, long refillRate, long cost) {
            long nowMs = System.currentTimeMillis();
            long elapsedMs = Math.max(0, nowMs - lastUpdatedMs);

            // Refill tokens
            double replenished = (elapsedMs / 1000.0) * refillRate;
            tokens = Math.min(capacity, tokens + replenished);
            lastUpdatedMs = nowMs;

            if (tokens >= cost) {
                tokens -= cost;
                long remaining = (long) Math.floor(tokens);
                long resetSeconds = (long) Math.ceil((capacity - tokens) / refillRate);
                return RateLimitResult.allow(capacity, remaining, Math.max(1, resetSeconds));
            } else {
                double missing = cost - tokens;
                long retryAfterSeconds = (long) Math.ceil(missing / refillRate);
                long resetSeconds = (long) Math.ceil((capacity - tokens) / refillRate);
                return RateLimitResult.reject(capacity, Math.max(1, resetSeconds), Math.max(1, retryAfterSeconds));
            }
        }
    }
}
