package com.engine.idemgate.idempotency.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.engine.idemgate.idempotency.IdempotencyStore;
import com.engine.idemgate.idempotency.LockAcquisitionResult;
import com.engine.idemgate.model.CachedHttpResponse;
import com.engine.idemgate.model.IdempotencyRecord;
import com.engine.idemgate.model.IdempotencyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory high-throughput store using Caffeine cache and Java CompletableFutures
 * to serialize concurrent duplicate requests without external dependencies.
 */
@Component
@ConditionalOnProperty(name = "idemgate.storage.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    private final Cache<String, IdempotencyRecord> cache;
    private final ConcurrentHashMap<String, CompletableFuture<CachedHttpResponse>> inFlightWaiters = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public InMemoryIdempotencyStore(Cache<String, IdempotencyRecord> idempotencyL1Cache) {
        this.cache = idempotencyL1Cache;
    }

    @Override
    public Mono<LockAcquisitionResult> acquireInFlight(String key, String fingerprint, int lockTtlSeconds) {
        synchronized (lock) {
            IdempotencyRecord existing = cache.getIfPresent(key);

            if (existing != null && !existing.isExpired()) {
                if (existing.getStatus() == IdempotencyStatus.RESOLVED) {
                    log.debug("Key {} is already RESOLVED in memory store", key);
                    return Mono.just(LockAcquisitionResult.alreadyResolved(existing));
                } else if (existing.getStatus() == IdempotencyStatus.IN_FLIGHT) {
                    log.debug("Key {} is currently IN_FLIGHT in memory store", key);
                    return Mono.just(LockAcquisitionResult.conflictInFlight(existing));
                }
            }

            // Key is absent or expired: acquire lock
            Instant expiresAt = Instant.now().plusSeconds(lockTtlSeconds);
            IdempotencyRecord inFlightRecord = IdempotencyRecord.inFlight(key, fingerprint, expiresAt);
            cache.put(key, inFlightRecord);
            inFlightWaiters.computeIfAbsent(key, k -> new CompletableFuture<>());

            log.debug("Acquired IN_FLIGHT lock for key: {}", key);
            return Mono.just(LockAcquisitionResult.acquired());
        }
    }

    @Override
    public Mono<Void> resolve(String key, String fingerprint, CachedHttpResponse response, int recordTtlSeconds) {
        CompletableFuture<CachedHttpResponse> future;
        synchronized (lock) {
            Instant expiresAt = Instant.now().plusSeconds(recordTtlSeconds);
            IdempotencyRecord resolvedRecord = IdempotencyRecord.resolved(key, fingerprint, response, expiresAt);
            cache.put(key, resolvedRecord);
            future = inFlightWaiters.remove(key);
        }

        if (future != null) {
            log.debug("Notifying {} waiting concurrent callers for key: {}", future.getNumberOfDependents(), key);
            future.complete(response);
        }

        return Mono.empty();
    }

    @Override
    public Mono<Void> releaseLock(String key) {
        CompletableFuture<CachedHttpResponse> future;
        synchronized (lock) {
            cache.invalidate(key);
            future = inFlightWaiters.remove(key);
        }

        if (future != null) {
            future.completeExceptionally(new IllegalStateException("In-flight execution was aborted"));
        }
        return Mono.empty();
    }

    @Override
    public Mono<IdempotencyRecord> get(String key) {
        IdempotencyRecord record = cache.getIfPresent(key);
        if (record != null && !record.isExpired()) {
            return Mono.just(record);
        }
        return Mono.empty();
    }

    @Override
    public Mono<CachedHttpResponse> awaitResolution(String key, int timeoutSeconds) {
        // Fast-path check
        IdempotencyRecord record = cache.getIfPresent(key);
        if (record != null && record.getStatus() == IdempotencyStatus.RESOLVED && record.getResponse() != null) {
            return Mono.just(record.getResponse());
        }

        CompletableFuture<CachedHttpResponse> future = inFlightWaiters.get(key);
        if (future != null) {
            return Mono.fromFuture(future)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .switchIfEmpty(Mono.defer(() -> {
                        IdempotencyRecord fallback = cache.getIfPresent(key);
                        if (fallback != null && fallback.getResponse() != null) {
                            return Mono.just(fallback.getResponse());
                        }
                        return Mono.empty();
                    }));
        }

        // Future was not present, re-check cache
        if (record != null && record.getResponse() != null) {
            return Mono.just(record.getResponse());
        }

        return Mono.error(new IllegalStateException("No in-flight execution found to await for key: " + key));
    }
}
