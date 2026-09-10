package com.engine.idemgate.observability;

import com.engine.idemgate.idempotency.IdempotencyStore;
import com.engine.idemgate.model.IdempotencyRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Administrative inspection endpoint to debug idempotency key records in storage.
 */
@RestController
@RequestMapping("/idemgate/api/v1/inspect")
public class InspectionController {

    private final IdempotencyStore store;

    public InspectionController(IdempotencyStore store) {
        this.store = store;
    }

    @GetMapping("/{key}")
    public Mono<ResponseEntity<Map<String, Object>>> inspectKey(@PathVariable("key") String key) {
        return store.get(key)
                .map(record -> ResponseEntity.ok(Map.<String, Object>of(
                        "key", record.getKey(),
                        "fingerprint", record.getRequestFingerprint(),
                        "status", record.getStatus().name(),
                        "createdAt", record.getCreatedAt().toString(),
                        "expiresAt", record.getExpiresAt() != null ? record.getExpiresAt().toString() : "none",
                        "hasCachedResponse", record.getResponse() != null,
                        "cachedStatusCode", record.getResponse() != null ? record.getResponse().getStatusCode() : 0
                )))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
