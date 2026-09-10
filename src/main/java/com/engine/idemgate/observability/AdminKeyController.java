package com.engine.idemgate.observability;

import com.engine.idemgate.idempotency.IdempotencyStore;
import com.engine.idemgate.model.IdempotencyRecord;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST management controller for inspecting, listing, and manually evicting idempotency keys,
 * as well as providing aggregated operational statistics for the dashboard.
 */
@RestController
@RequestMapping("/idemgate/api/v1")
public class AdminKeyController {

    private final IdempotencyStore store;
    private final MeterRegistry meterRegistry;

    public AdminKeyController(IdempotencyStore store, MeterRegistry meterRegistry) {
        this.store = store;
        this.meterRegistry = meterRegistry;
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

                    stats.put("totalRequests", (long) totalRequests);
                    stats.put("cacheHits", (long) cacheHits);
                    stats.put("cacheMisses", (long) cacheMisses);
                    stats.put("raceConditionsSerialized", (long) raceSerialized);
                    stats.put("payloadMismatches", (long) payloadMismatches);
                    stats.put("rateLimitedRequests", (long) rateLimited);
                    stats.put("cacheHitRatioPercent", Math.round(hitRatio * 10.0) / 10.0);
                    stats.put("activeCachedKeys", records.size());

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
