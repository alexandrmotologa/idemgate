package com.engine.idemgate.idempotency;

import com.engine.idemgate.config.IdemGateProperties;
import com.engine.idemgate.model.CachedHttpResponse;
import com.engine.idemgate.model.IdempotencyRecord;
import com.engine.idemgate.model.ProblemDetailDto;
import com.engine.idemgate.observability.IdemMetrics;
import com.engine.idemgate.proxy.RequestFingerprinter;
import com.engine.idemgate.proxy.UpstreamDispatcher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * Core engine enforcing the IETF Idempotency-Key specification, fingerprint digest validation,
 * distributed locking, and concurrent race serialization.
 */
@Component
public class IdempotencyEngine {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyEngine.class);

    private static final URI RFC_SECTION_2_7_MISMATCH =
            URI.create("https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-04#section-2.7");
    private static final URI RFC_SECTION_2_1_INVALID_KEY =
            URI.create("https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-04#section-2.1");
    private static final URI RFC_GATEWAY_TIMEOUT =
            URI.create("https://datatracker.ietf.org/doc/html/rfc7231#section-6.6.5");

    private final IdempotencyStore store;
    private final UpstreamDispatcher upstreamDispatcher;
    private final RequestFingerprinter fingerprinter;
    private final IdemGateProperties properties;
    private final IdemMetrics metrics;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public IdempotencyEngine(
            IdempotencyStore store,
            UpstreamDispatcher upstreamDispatcher,
            RequestFingerprinter fingerprinter,
            IdemGateProperties properties,
            IdemMetrics metrics,
            ObjectMapper objectMapper) {
        this.store = store;
        this.upstreamDispatcher = upstreamDispatcher;
        this.fingerprinter = fingerprinter;
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    public Mono<CachedHttpResponse> handle(
            HttpMethod method,
            String path,
            String query,
            HttpHeaders headers,
            byte[] body) {

        String headerName = properties.getIdempotency().getHeaderName();
        String idempotencyKey = headers.getFirst(headerName);

        boolean routeRequiresKey = isKeyRequiredForPath(path);

        // Check missing key
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            if (routeRequiresKey) {
                return Mono.just(createProblemDetailResponse(
                        HttpStatus.BAD_REQUEST,
                        RFC_SECTION_2_1_INVALID_KEY,
                        "Missing Idempotency-Key",
                        "An Idempotency-Key header is required for this route.",
                        path
                ));
            }
            // Forward directly without idempotency tracking
            return upstreamDispatcher.forward(method, path, query, headers, body);
        }

        // Validate key length and format
        if (idempotencyKey.length() > 256) {
            return Mono.just(createProblemDetailResponse(
                    HttpStatus.BAD_REQUEST,
                    RFC_SECTION_2_1_INVALID_KEY,
                    "Invalid Idempotency-Key Length",
                    "Idempotency-Key exceeds the maximum allowed length of 256 characters.",
                    path
            ));
        }

        // Compute SHA-256 fingerprint
        String fingerprint = fingerprinter.computeFingerprint(method, path, query, body);
        int lockTtl = properties.getIdempotency().getLockTtlSeconds();
        int recordTtl = properties.getIdempotency().getRecordTtlSeconds();
        int waitTimeout = properties.getIdempotency().getWaitTimeoutSeconds();

        return store.acquireInFlight(idempotencyKey, fingerprint, lockTtl)
                .flatMap(result -> switch (result.getStatus()) {
                    case ACQUIRED -> executePrimaryRequest(idempotencyKey, fingerprint, method, path, query, headers, body, recordTtl);
                    case ALREADY_RESOLVED -> replayCachedRecord(result.getExistingRecord(), fingerprint, path);
                    case CONFLICT_IN_FLIGHT -> awaitInFlightResolution(idempotencyKey, result.getExistingRecord(), fingerprint, path, waitTimeout);
                });
    }

    private Mono<CachedHttpResponse> executePrimaryRequest(
            String key,
            String fingerprint,
            HttpMethod method,
            String path,
            String query,
            HttpHeaders headers,
            byte[] body,
            int recordTtl) {

        log.debug("Executing primary upstream request for idempotency key: {}", key);
        metrics.incrementCacheMiss();
        long startNanos = System.nanoTime();

        return upstreamDispatcher.forward(method, path, query, headers, body)
                .flatMap(response -> {
                    metrics.recordUpstreamLatency(System.nanoTime() - startNanos);

                    // Add Idempotent-Replayed: false header
                    CachedHttpResponse modifiedResponse = appendHeader(response, "Idempotent-Replayed", "false");

                    // Save as RESOLVED in cache
                    return store.resolve(key, fingerprint, modifiedResponse, recordTtl)
                            .thenReturn(modifiedResponse);
                })
                .onErrorResume(error -> {
                    log.error("Upstream execution failed for key: {}. Releasing in-flight lock.", key, error);
                    return store.releaseLock(key)
                            .then(Mono.error(error));
                });
    }

    private Mono<CachedHttpResponse> replayCachedRecord(
            IdempotencyRecord record,
            String currentFingerprint,
            String path) {

        if (!record.getRequestFingerprint().equals(currentFingerprint)) {
            log.warn("Payload fingerprint mismatch for key: {}", record.getKey());
            metrics.incrementPayloadMismatch();
            return Mono.just(createProblemDetailResponse(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    RFC_SECTION_2_7_MISMATCH,
                    "Idempotency Key Payload Mismatch",
                    "The provided Idempotency-Key was previously used with a different request payload.",
                    path
            ));
        }

        log.debug("Replaying cached response for idempotency key: {}", record.getKey());
        metrics.incrementCacheHit();

        CachedHttpResponse cached = record.getResponse();
        CachedHttpResponse replayed = appendHeader(cached, "Idempotent-Replayed", "true");

        String formattedDate = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(cached.getCreatedAt().atOffset(ZoneOffset.UTC));
        replayed = appendHeader(replayed, "Original-Date", formattedDate);

        return Mono.just(replayed);
    }

    private Mono<CachedHttpResponse> awaitInFlightResolution(
            String key,
            IdempotencyRecord inFlightRecord,
            String currentFingerprint,
            String path,
            int waitTimeout) {

        // If existing in-flight record already has a mismatching fingerprint, fail immediately
        if (inFlightRecord != null && inFlightRecord.getRequestFingerprint() != null
                && !inFlightRecord.getRequestFingerprint().equals(currentFingerprint)) {
            log.warn("In-flight payload fingerprint mismatch for key: {}", key);
            metrics.incrementPayloadMismatch();
            return Mono.just(createProblemDetailResponse(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    RFC_SECTION_2_7_MISMATCH,
                    "Idempotency Key Payload Mismatch",
                    "The provided Idempotency-Key was previously used with a different request payload.",
                    path
            ));
        }

        log.info("Serializing concurrent request for in-flight key: {}. Awaiting resolution...", key);
        metrics.incrementRaceSerialized();

        return store.awaitResolution(key, waitTimeout)
                .flatMap(response -> {
                    metrics.incrementCacheHit();
                    CachedHttpResponse replayed = appendHeader(response, "Idempotent-Replayed", "true");
                    String formattedDate = DateTimeFormatter.RFC_1123_DATE_TIME
                            .format(response.getCreatedAt().atOffset(ZoneOffset.UTC));
                    replayed = appendHeader(replayed, "Original-Date", formattedDate);
                    return Mono.just(replayed);
                })
                .onErrorResume(TimeoutException.class, e -> {
                    log.error("Timed out awaiting in-flight resolution for key: {}", key);
                    return Mono.just(createProblemDetailResponse(
                            HttpStatus.GATEWAY_TIMEOUT,
                            RFC_GATEWAY_TIMEOUT,
                            "Gateway Timeout",
                            "Timed out waiting for concurrent in-flight request to resolve.",
                            path
                    ));
                });
    }

    private boolean isKeyRequiredForPath(String path) {
        for (IdemGateProperties.RouteRule rule : properties.getIdempotency().getRoutes()) {
            if (pathMatcher.match(rule.getPath(), path)) {
                return rule.isRequired();
            }
        }
        return false;
    }

    private CachedHttpResponse appendHeader(CachedHttpResponse response, String headerName, String headerValue) {
        Map<String, List<String>> newHeaders = new HashMap<>(response.getHeaders());
        newHeaders.put(headerName, List.of(headerValue));
        return new CachedHttpResponse(
                response.getStatusCode(),
                newHeaders,
                response.getBody(),
                response.getCreatedAt()
        );
    }

    private CachedHttpResponse createProblemDetailResponse(
            HttpStatus status,
            URI type,
            String title,
            String detail,
            String instance) {

        ProblemDetailDto problemDetail = ProblemDetailDto.of(type, title, status.value(), detail, instance);
        byte[] bodyBytes;
        try {
            bodyBytes = objectMapper.writeValueAsBytes(problemDetail);
        } catch (JsonProcessingException e) {
            bodyBytes = "{\"title\":\"Error\"}".getBytes(StandardCharsets.UTF_8);
        }

        Map<String, List<String>> headers = Map.of(
                HttpHeaders.CONTENT_TYPE, List.of(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        );

        return new CachedHttpResponse(status.value(), headers, bodyBytes, Instant.now());
    }
}
