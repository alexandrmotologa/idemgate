package com.engine.idemgate.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embedded mock service used for integration tests, benchmarks, and local development.
 * Tracks invocation counts to verify exact-once processing.
 */
@RestController
@RequestMapping("/upstream-mock")
public class MockUpstreamController {

    private static final Logger log = LoggerFactory.getLogger(MockUpstreamController.class);

    private final AtomicInteger invocationCounter = new AtomicInteger(0);
    private final Map<String, AtomicInteger> endpointCounters = new ConcurrentHashMap<>();

    @PostMapping(value = "/**", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> handleGenericPost(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader HttpHeaders headers,
            @RequestParam(value = "delay", defaultValue = "0") long delayMs) {

        int currentCount = invocationCounter.incrementAndGet();
        log.info("MockUpstream received POST request. Total invocations: {}", currentCount);

        long effectiveDelay = delayMs;
        String delayHeader = headers.getFirst("X-Mock-Delay");
        if (delayHeader != null && !delayHeader.isBlank()) {
            try {
                effectiveDelay = Long.parseLong(delayHeader);
            } catch (NumberFormatException ignored) {
            }
        }

        Map<String, Object> responsePayload = Map.of(
                "status", "SUCCESS",
                "transactionId", UUID.randomUUID().toString(),
                "upstreamInvocations", currentCount,
                "receivedData", body != null ? body : Map.of()
        );

        ResponseEntity<Map<String, Object>> response = ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Upstream-Server", "idemgate-embedded-mock")
                .body(responsePayload);

        if (effectiveDelay > 0) {
            return Mono.delay(Duration.ofMillis(effectiveDelay)).thenReturn(response);
        }
        return Mono.just(response);
    }

    @GetMapping("/invocations")
    public Mono<Map<String, Object>> getInvocationStats() {
        return Mono.just(Map.of(
                "totalInvocations", invocationCounter.get()
        ));
    }

    @PostMapping("/invocations/reset")
    public Mono<Map<String, Object>> resetInvocations() {
        invocationCounter.set(0);
        endpointCounters.clear();
        return Mono.just(Map.of("message", "Invocation counter reset to 0"));
    }
}
