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
        String tenantId = "tenant-replenish-test";
        long capacity = 2;
        long refillRate = 10; // 10 tokens per second (fast refill for testing)

        // Drain bucket
        rateLimiter.tryAcquire(tenantId, capacity, refillRate, 1).block();
        rateLimiter.tryAcquire(tenantId, capacity, refillRate, 1).block();

        RateLimitResult exhausted = rateLimiter.tryAcquire(tenantId, capacity, refillRate, 1).block();
        assertThat(exhausted.isAllowed()).isFalse();

        // Wait 250ms -> should replenish at least 2 tokens (0.25s * 10 = 2.5 tokens)
        Thread.sleep(250);

        RateLimitResult replenished = rateLimiter.tryAcquire(tenantId, capacity, refillRate, 1).block();
        assertThat(replenished.isAllowed()).isTrue();
    }
}
