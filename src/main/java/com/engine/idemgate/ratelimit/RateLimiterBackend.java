package com.engine.idemgate.ratelimit;

import com.engine.idemgate.model.RateLimitResult;
import reactor.core.publisher.Mono;

/**
 * Pluggable backend SPI for token bucket rate limiting.
 */
public interface RateLimiterBackend {

    /**
     * Attempts to consume tokens from the tenant's bucket.
     *
     * @param tenantId unique tenant identifier
     * @param capacity maximum token burst capacity
     * @param refillRate tokens replenished per second
     * @param cost tokens requested to consume (typically 1)
     * @return RateLimitResult indicating whether allowed, remaining tokens, and reset times
     */
    Mono<RateLimitResult> tryAcquire(String tenantId, long capacity, long refillRate, long cost);
}
