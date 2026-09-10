package com.engine.idemgate.idempotency.impl;

import com.engine.idemgate.idempotency.IdempotencyStore;
import com.engine.idemgate.idempotency.LockAcquisitionResult;
import com.engine.idemgate.model.CachedHttpResponse;
import com.engine.idemgate.model.IdempotencyRecord;
import com.engine.idemgate.model.IdempotencyStatus;
import org.redisson.api.RLock;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Distributed idempotency store backed by Redis 7+ and Redisson.
 * Uses distributed locks and Redis Pub/Sub topics to coordinate parallel requests
 * across multiple IdemGate proxy instances.
 */
@Component
@ConditionalOnProperty(name = "idemgate.storage.type", havingValue = "redis")
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);
    private static final String KEY_PREFIX = "idem:record:";
    private static final String LOCK_PREFIX = "idem:lock:";
    private static final String TOPIC_PREFIX = "idem:resolved:";

    private final RedissonClient redissonClient;
    private final RedisTemplate<String, IdempotencyRecord> redisTemplate;

    public RedisIdempotencyStore(
            RedissonClient redissonClient,
            RedisTemplate<String, IdempotencyRecord> redisTemplate) {
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<LockAcquisitionResult> acquireInFlight(String key, String fingerprint, int lockTtlSeconds) {
        return Mono.fromCallable(() -> {
            String redisKey = KEY_PREFIX + key;
            IdempotencyRecord existing = redisTemplate.opsForValue().get(redisKey);

            if (existing != null && !existing.isExpired()) {
                if (existing.getStatus() == IdempotencyStatus.RESOLVED) {
                    return LockAcquisitionResult.alreadyResolved(existing);
                } else if (existing.getStatus() == IdempotencyStatus.IN_FLIGHT) {
                    return LockAcquisitionResult.conflictInFlight(existing);
                }
            }

            RLock lock = redissonClient.getLock(LOCK_PREFIX + key);
            boolean acquired = lock.tryLock(500, lockTtlSeconds * 1000L, TimeUnit.MILLISECONDS);
            if (!acquired) {
                // Another node took lock concurrently
                IdempotencyRecord raceRecord = redisTemplate.opsForValue().get(redisKey);
                if (raceRecord != null && raceRecord.getStatus() == IdempotencyStatus.RESOLVED) {
                    return LockAcquisitionResult.alreadyResolved(raceRecord);
                }
                return LockAcquisitionResult.conflictInFlight(raceRecord);
            }

            // Lock acquired: write IN_FLIGHT state
            Instant expiresAt = Instant.now().plusSeconds(lockTtlSeconds);
            IdempotencyRecord inFlightRecord = IdempotencyRecord.inFlight(key, fingerprint, expiresAt);
            redisTemplate.opsForValue().set(redisKey, inFlightRecord, lockTtlSeconds, TimeUnit.SECONDS);

            return LockAcquisitionResult.acquired();
        });
    }

    @Override
    public Mono<Void> resolve(String key, String fingerprint, CachedHttpResponse response, int recordTtlSeconds) {
        return Mono.fromRunnable(() -> {
            String redisKey = KEY_PREFIX + key;
            Instant expiresAt = Instant.now().plusSeconds(recordTtlSeconds);
            IdempotencyRecord resolvedRecord = IdempotencyRecord.resolved(key, fingerprint, response, expiresAt);
            redisTemplate.opsForValue().set(redisKey, resolvedRecord, recordTtlSeconds, TimeUnit.SECONDS);

            // Notify waiting nodes via Pub/Sub
            RTopic topic = redissonClient.getTopic(TOPIC_PREFIX + key);
            topic.publish(key);

            // Unlock
            RLock lock = redissonClient.getLock(LOCK_PREFIX + key);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        });
    }

    @Override
    public Mono<Void> releaseLock(String key) {
        return Mono.fromRunnable(() -> {
            redisTemplate.delete(KEY_PREFIX + key);
            RLock lock = redissonClient.getLock(LOCK_PREFIX + key);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        });
    }

    @Override
    public Mono<IdempotencyRecord> get(String key) {
        return Mono.fromCallable(() -> redisTemplate.opsForValue().get(KEY_PREFIX + key));
    }

    @Override
    public Mono<CachedHttpResponse> awaitResolution(String key, int timeoutSeconds) {
        // Fast-path: check if already resolved
        IdempotencyRecord immediate = redisTemplate.opsForValue().get(KEY_PREFIX + key);
        if (immediate != null && immediate.getStatus() == IdempotencyStatus.RESOLVED && immediate.getResponse() != null) {
            return Mono.just(immediate.getResponse());
        }

        CompletableFuture<CachedHttpResponse> notificationFuture = new CompletableFuture<>();
        RTopic topic = redissonClient.getTopic(TOPIC_PREFIX + key);

        int listenerId = topic.addListener(String.class, (channel, msg) -> {
            IdempotencyRecord record = redisTemplate.opsForValue().get(KEY_PREFIX + key);
            if (record != null && record.getResponse() != null) {
                notificationFuture.complete(record.getResponse());
            }
        });

        return Mono.fromFuture(notificationFuture)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .doFinally(signalType -> topic.removeListener(listenerId));
    }

    @Override
    public Mono<Boolean> evict(String key) {
        return Mono.fromCallable(() -> {
            Boolean deleted = redisTemplate.delete(KEY_PREFIX + key);
            RLock lock = redissonClient.getLock(LOCK_PREFIX + key);
            if (lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            return Boolean.TRUE.equals(deleted);
        });
    }

    @Override
    public Mono<java.util.List<IdempotencyRecord>> listKeys(int limit) {
        return Mono.fromCallable(() -> {
            java.util.Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            java.util.List<IdempotencyRecord> records = new java.util.ArrayList<>();
            for (String redisKey : keys) {
                IdempotencyRecord rec = redisTemplate.opsForValue().get(redisKey);
                if (rec != null && !rec.isExpired()) {
                    records.add(rec);
                    if (records.size() >= limit) {
                        break;
                    }
                }
            }
            return records;
        });
    }
}
