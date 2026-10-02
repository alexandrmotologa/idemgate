package com.engine.idemgate.observability;

import com.engine.idemgate.config.IdemGateProperties;
import com.engine.idemgate.idempotency.IdempotencyStore;
import com.engine.idemgate.model.IdempotencyRecord;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST management controller for inspecting, listing, and manually evicting idempotency keys,
 * exporting configuration, and providing aggregated operational statistics for the dashboard.
 */
@RestController
@RequestMapping("/idemgate/api/v1")
public class AdminKeyController {

    private final IdempotencyStore store;
    private final MeterRegistry meterRegistry;
    private final IdemGateProperties properties;

    public AdminKeyController(IdempotencyStore store, MeterRegistry meterRegistry, IdemGateProperties properties) {
        this.store = store;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
    }

    @GetMapping("/keys")
    public Mono<ResponseEntity<Map<String, Object>>> listKeys(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return store.listKeys(Math.min(limit, 500))
                .map(records -> {
                    List<Map<String, Object>> keyList = records.stream()
                            .map(this::toMap)
                            .toList();
                    return ResponseEntity.ok(Map.of(
                            "count", keyList.size(),
                            "keys", keyList
                    ));
                });
    }

    @DeleteMapping("/keys/{key}")
    public Mono<ResponseEntity<Map<String, Object>>> evictKey(@PathVariable("key") String key) {
        return store.evict(key)
                .map(evicted -> ResponseEntity.ok(Map.of(
                        "key", key,
                        "evicted", evicted,
                        "message", evicted ? "Key successfully evicted" : "Key not found"
                )));
    }

    @DeleteMapping("/keys")
    public Mono<ResponseEntity<Map<String, Object>>> evictAllKeys() {
        return store.evictAll()
                .map(count -> ResponseEntity.ok(Map.of(
                        "evictedCount", count,
                        "message", "All idempotency keys successfully evicted"
                )));
    }

    @GetMapping("/config")
    public Mono<ResponseEntity<Map<String, Object>>> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("storageType", properties.getStorage().getType());
        config.put("upstreamUrl", properties.getUpstream().getUrl());
        config.put("idempotencyHeader", properties.getIdempotency().getHeaderName());
        config.put("lockTtlSeconds", properties.getIdempotency().getLockTtlSeconds());
        config.put("recordTtlSeconds", properties.getIdempotency().getRecordTtlSeconds());
        config.put("maxBodySizeBytes", properties.getIdempotency().getMaxBodySizeBytes());
        config.put("rateLimitEnabled", properties.getRateLimit().isEnabled());
        config.put("defaultCapacity", properties.getRateLimit().getDefaultCapacity());
        config.put("defaultRefillRate", properties.getRateLimit().getDefaultRefillRate());
        config.put("fingerprintHeaders", properties.getIdempotency().getFingerprint().getIncludeHeaders());
        config.put("tiers", properties.getRateLimit().getTiers());
        config.put("routeRules", properties.getRateLimit().getRouteRules());
        return Mono.just(ResponseEntity.ok(config));
    }

    @GetMapping("/stats")
    public Mono<ResponseEntity<Map<String, Object>>> getLiveStats() {
        return store.listKeys(500)
                .map(records -> {
                    Map<String, Object> stats = new HashMap<>();

                    double totalRequests = getCounterValue("idemgate.requests.total");
                    double cacheHits = getCounterValue("idemgate.cache.hit");
                    double cacheMisses = getCounterValue("idemgate.cache.miss");
                    double raceSerialized = getCounterValue("idemgate.race_condition.serialized");
                    double payloadMismatches = getCounterValue("idemgate.key.payload_mismatch");
                    double rateLimited = getCounterValue("idemgate.rate_limited");

                    double hitRatio = (cacheHits + cacheMisses) > 0
                            ? (cacheHits / (cacheHits + cacheMisses)) * 100.0
                            : 0.0;

                    Timer proxyTimer = meterRegistry.find("idemgate.proxy.latency").timer();
                    double meanLatencyMs = proxyTimer != null ? proxyTimer.mean(TimeUnit.MILLISECONDS) : 0.0;
                    double maxLatencyMs = proxyTimer != null ? proxyTimer.max(TimeUnit.MILLISECONDS) : 0.0;

                    stats.put("storageType", properties.getStorage().getType());
                    stats.put("totalRequests", (long) totalRequests);
                    stats.put("cacheHits", (long) cacheHits);
                    stats.put("cacheMisses", (long) cacheMisses);
                    stats.put("raceConditionsSerialized", (long) raceSerialized);
                    stats.put("payloadMismatches", (long) payloadMismatches);
                    stats.put("rateLimitedRequests", (long) rateLimited);
                    stats.put("cacheHitRatioPercent", Math.round(hitRatio * 10.0) / 10.0);
                    stats.put("activeCachedKeys", records.size());
                    stats.put("meanProxyLatencyMs", Math.round(meanLatencyMs * 100.0) / 100.0);
                    stats.put("maxProxyLatencyMs", Math.round(maxLatencyMs * 100.0) / 100.0);

                    return ResponseEntity.ok(stats);
                });
    }

    private double getCounterValue(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private Map<String, Object> toMap(IdempotencyRecord record) {
        Map<String, Object> map = new HashMap<>();
        map.put("key", record.getKey());
        map.put("fingerprint", record.getRequestFingerprint());
        map.put("status", record.getStatus().name());
        map.put("createdAt", record.getCreatedAt().toString());
        map.put("expiresAt", record.getExpiresAt() != null ? record.getExpiresAt().toString() : null);
        map.put("statusCode", record.getResponse() != null ? record.getResponse().getStatusCode() : null);
        map.put("bodySizeBytes", record.getResponse() != null && record.getResponse().getBody() != null
                ? record.getResponse().getBody().length
                : 0);
        return map;
    }
}
