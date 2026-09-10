package com.engine.idemgate.idempotency;

import com.engine.idemgate.model.CachedHttpResponse;
import com.engine.idemgate.model.IdempotencyRecord;
import reactor.core.publisher.Mono;

/**
 * Storage SPI for managing idempotency key lifecycle, concurrency locks, and cached responses.
 */
public interface IdempotencyStore {

    /**
     * Attempts to acquire in-flight execution rights for a given idempotency key.
     *
     * @param key Idempotency key
     * @param fingerprint SHA-256 digest of request components
     * @param lockTtlSeconds In-flight lock lease duration
     * @return LockAcquisitionResult indicating whether the caller acquired the lock or must wait/replay
     */
    Mono<LockAcquisitionResult> acquireInFlight(String key, String fingerprint, int lockTtlSeconds);

    /**
     * Resolves the idempotency key with the final HTTP response and caches it for long-term replay.
     * Also notifies any concurrent callers waiting on this key.
     *
     * @param key Idempotency key
     * @param fingerprint SHA-256 digest of request components
     * @param response Cached HTTP response
     * @param recordTtlSeconds Cache expiration TTL
     * @return Completion signal
     */
    Mono<Void> resolve(String key, String fingerprint, CachedHttpResponse response, int recordTtlSeconds);

    /**
     * Releases or cancels an in-flight lock (e.g. if upstream processing failed with an unretryable network error).
     *
     * @param key Idempotency key
     * @return Completion signal
     */
    Mono<Void> releaseLock(String key);

    /**
     * Retrieves an idempotency record by key.
     *
     * @param key Idempotency key
     * @return Mono emitting the record or empty if not found
     */
    Mono<IdempotencyRecord> get(String key);

    /**
     * Suspends the calling reactive pipeline until the in-flight key is resolved by the primary request.
     *
     * @param key Idempotency key
     * @param timeoutSeconds Maximum duration to wait
     * @return Mono emitting the resolved response, or timing out with TimeoutException
     */
    Mono<CachedHttpResponse> awaitResolution(String key, int timeoutSeconds);

    /**
     * Manually evicts an idempotency key from cache and cancels any pending locks.
     *
     * @param key Idempotency key
     * @return Mono emitting true if key existed and was evicted, false otherwise
     */
    Mono<Boolean> evict(String key);

    /**
     * Lists active idempotency records up to the specified limit.
     *
     * @param limit Maximum number of records to return
     * @return Mono emitting list of records
     */
    Mono<java.util.List<IdempotencyRecord>> listKeys(int limit);
}
