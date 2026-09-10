package com.engine.idemgate.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;

/**
 * Represents the persistent or in-memory state of an idempotency key.
 */
public class IdempotencyRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String key;
    private final String requestFingerprint;
    private final IdempotencyStatus status;
    private final CachedHttpResponse response;
    private final Instant createdAt;
    private final Instant expiresAt;

    @JsonCreator
    public IdempotencyRecord(
            @JsonProperty("key") String key,
            @JsonProperty("requestFingerprint") String requestFingerprint,
            @JsonProperty("status") IdempotencyStatus status,
            @JsonProperty("response") CachedHttpResponse response,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("expiresAt") Instant expiresAt) {
        this.key = key;
        this.requestFingerprint = requestFingerprint;
        this.status = status;
        this.response = response;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.expiresAt = expiresAt;
    }

    public static IdempotencyRecord inFlight(String key, String requestFingerprint, Instant expiresAt) {
        return new IdempotencyRecord(key, requestFingerprint, IdempotencyStatus.IN_FLIGHT, null, Instant.now(), expiresAt);
    }

    public static IdempotencyRecord resolved(String key, String requestFingerprint, CachedHttpResponse response, Instant expiresAt) {
        return new IdempotencyRecord(key, requestFingerprint, IdempotencyStatus.RESOLVED, response, Instant.now(), expiresAt);
    }

    public String getKey() {
        return key;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public CachedHttpResponse getResponse() {
        return response;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
