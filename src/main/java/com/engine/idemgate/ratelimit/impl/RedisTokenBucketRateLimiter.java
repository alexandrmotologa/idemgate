package com.engine.idemgate.ratelimit.impl;

import com.engine.idemgate.model.RateLimitResult;
import com.engine.idemgate.ratelimit.RateLimiterBackend;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Distributed token bucket rate limiter backed by Redis atomic Lua scripts.
 */
@Component
@ConditionalOnProperty(name = "idemgate.storage.type", havingValue = "redis")
public class RedisTokenBucketRateLimiter implements RateLimiterBackend {

    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate stringRedisTemplate;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> tokenBucketScript;

    @SuppressWarnings("rawtypes")
    public RedisTokenBucketRateLimiter(
            StringRedisTemplate stringRedisTemplate,
            DefaultRedisScript<List> tokenBucketScript) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.tokenBucketScript = tokenBucketScript;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<RateLimitResult> tryAcquire(String tenantId, long capacity, long refillRate, long cost) {
        return Mono.fromCallable(() -> {
            String key = KEY_PREFIX + tenantId;
            long nowMs = System.currentTimeMillis();

            List<Object> result = stringRedisTemplate.execute(
                    tokenBucketScript,
                    List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(refillRate),
                    String.valueOf(cost),
                    String.valueOf(nowMs)
            );

            if (result == null || result.size() < 3) {
                // Fallback allow if script returned null
                return RateLimitResult.allow(capacity, capacity - cost, 1);
            }

            long allowed = ((Number) result.get(0)).longValue();
            long remaining = ((Number) result.get(1)).longValue();
            long resetMs = ((Number) result.get(2)).longValue();
            long resetSeconds = Math.max(1, (resetMs + 999) / 1000);

            if (allowed == 1) {
                return RateLimitResult.allow(capacity, remaining, resetSeconds);
            } else {
                long retryAfterSeconds = Math.max(1, resetSeconds);
                return RateLimitResult.reject(capacity, resetSeconds, retryAfterSeconds);
            }
        });
    }
}
