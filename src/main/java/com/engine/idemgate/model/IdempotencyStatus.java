package com.engine.idemgate.model;

/**
 * State machine of an idempotency key lifecycle.
 */
public enum IdempotencyStatus {
    /**
     * The first request with this key is currently executing upstream.
     */
    IN_FLIGHT,

    /**
     * The upstream execution completed and the response is cached.
     */
    RESOLVED
}
