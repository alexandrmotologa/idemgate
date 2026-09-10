package com.engine.idemgate.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Custom Prometheus metrics for IdemGate proxy operations.
 */
@Component
public class IdemMetrics {

    private final Counter requestsTotal;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter raceConditionsSerialized;
    private final Counter payloadMismatches;
    private final Counter rateLimitedTotal;
    private final Timer proxyLatency;
    private final Timer upstreamLatency;

    public IdemMetrics(MeterRegistry registry) {
        this.requestsTotal = Counter.builder("idemgate.requests.total")
                .description("Total number of requests processed by IdemGate")
                .register(registry);

        this.cacheHits = Counter.builder("idemgate.cache.hit")
                .description("Total number of idempotent responses served from cache")
                .register(registry);

        this.cacheMisses = Counter.builder("idemgate.cache.miss")
                .description("Total number of requests forwarded to upstream backend")
                .register(registry);

        this.raceConditionsSerialized = Counter.builder("idemgate.race_condition.serialized")
                .description("Total number of concurrent race conditions serialized")
                .register(registry);

        this.payloadMismatches = Counter.builder("idemgate.key.payload_mismatch")
                .description("Total number of idempotency key reuse attempts with mismatched payload")
                .register(registry);

        this.rateLimitedTotal = Counter.builder("idemgate.rate_limited")
                .description("Total number of requests throttled by rate limiter")
                .register(registry);

        this.proxyLatency = Timer.builder("idemgate.proxy.latency")
                .description("Total end-to-end proxy processing latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.upstreamLatency = Timer.builder("idemgate.upstream.latency")
                .description("Upstream backend call latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void incrementRequests() {
        requestsTotal.increment();
    }

    public void incrementCacheHit() {
        cacheHits.increment();
    }

    public void incrementCacheMiss() {
        cacheMisses.increment();
    }

    public void incrementRaceSerialized() {
        raceConditionsSerialized.increment();
    }

    public void incrementPayloadMismatch() {
        payloadMismatches.increment();
    }

    public void incrementRateLimited() {
        rateLimitedTotal.increment();
    }

    public void recordProxyLatency(long durationNanos) {
        proxyLatency.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordUpstreamLatency(long durationNanos) {
        upstreamLatency.record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
