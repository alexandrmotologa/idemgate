package com.engine.idemgate;

import com.engine.idemgate.model.RateLimitResult;
import com.engine.idemgate.ratelimit.impl.InMemoryTokenBucketRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterIntegrationTest {

    private InMemoryTokenBucketRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new InMemoryTokenBucketRateLimiter();
    }

    @Test
    @DisplayName("Should allow requests within bucket capacity and throttle when exhausted")
    void shouldAllowWithinCapacityAndThrottleWhenExhausted() {
        String tenantId = "tenant-test-100";
        long capacity = 3;
        long refillRate = 1; // 1 token per second

        // 1st request
        RateLimitResult r1 = rateLimiter.tryAcquire(tenantId, capacity, refillRate, 1).block();
        assertThat(r1).isNotNull();
        assertThat(r1.isAllowed()).isTrue();
        assertThat(r1.getRemaining()).isEqualTo(2);

        // 2nd request
        RateLimitResult r2 = rateLimiter.tryAcquire(tenantId, capacity, refillRate, 1).block();
        assertThat(r2).isNotNull();
        assertThat(r2.isAllowed()).isTrue();
        assertThat(r2.getRemaining()).isEqualTo(1);

        // 3rd request (exhausts bucket)
        RateLimitResult r3 = rateLimiter.tryAcquire(tenantId, capacity, refillRate, 1).block();
        assertThat(r3).isNotNull();
        assertThat(r3.isAllowed()).isTrue();
        assertThat(r3.getRemaining()).isEqualTo(0);

        // 4th request (should be throttled)
        RateLimitResult r4 = rateLimiter.tryAcquire(tenantId, capacity, refillRate, 1).block();
        assertThat(r4).isNotNull();
        assertThat(r4.isAllowed()).isFalse();
        assertThat(r4.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Should replenish tokens over time")
    void shouldReplenishTokensOverTime() throws InterruptedException {
        String tenantId = "tenant-replenish-test-" + System.nanoTime();
        long capacity = 2;
        long refillRate = 2; // 2 tokens per second (1 token per 500ms)

        // Drain entire bucket in one operation
        RateLimitResult drain = rateLimiter.tryAcquire(tenantId, capacity, refillRate, 2).block();
        assertThat(drain).isNotNull();
        assertThat(drain.isAllowed()).isTrue();

        // Immediate next request must be throttled
        RateLimitResult exhausted = rateLimiter.tryAcquire(tenantId, capacity, refillRate, 1).block();
        assertThat(exhausted).isNotNull();
        assertThat(exhausted.isAllowed()).isFalse();

        // Wait 600ms -> replenishes at least 1.2 tokens
        Thread.sleep(600);

        RateLimitResult replenished = rateLimiter.tryAcquire(tenantId, capacity, refillRate, 1).block();
        assertThat(replenished).isNotNull();
        assertThat(replenished.isAllowed()).isTrue();
    }
}
