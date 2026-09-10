package com.engine.idemgate.proxy;

import com.engine.idemgate.config.IdemGateProperties;
import com.engine.idemgate.idempotency.IdempotencyEngine;
import com.engine.idemgate.model.CachedHttpResponse;
import com.engine.idemgate.model.ProblemDetailDto;
import com.engine.idemgate.model.RateLimitResult;
import com.engine.idemgate.observability.IdemMetrics;
import com.engine.idemgate.ratelimit.RateLimiterService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * High-throughput reactive WebFilter that acts as the entrypoint for proxied traffic,
 * enforcing rate limiting, idempotency validation, concurrency serialization, and caching.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class ReverseProxyFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ReverseProxyFilter.class);

    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/actuator",
            "/upstream-mock",
            "/idemgate"
    );

    private final IdempotencyEngine idempotencyEngine;
    private final RateLimiterService rateLimiterService;
    private final IdemMetrics metrics;
    private final IdemGateProperties properties;
    private final ObjectMapper objectMapper;

    public ReverseProxyFilter(
            IdempotencyEngine idempotencyEngine,
            RateLimiterService rateLimiterService,
            IdemMetrics metrics,
            IdemGateProperties properties,
            ObjectMapper objectMapper) {
        this.idempotencyEngine = idempotencyEngine;
        this.rateLimiterService = rateLimiterService;
        this.metrics = metrics;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return chain.filter(exchange);
            }
        }

        long startNanos = System.nanoTime();
        metrics.incrementRequests();

        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        // 1. Rate Limiting Evaluation
        return rateLimiterService.checkRateLimit(request)
                .flatMap(rateResult -> {
                    applyRateLimitHeaders(response, rateResult);

                    if (!rateResult.isAllowed()) {
                        return writeRateLimitExceededResponse(response, path, rateResult);
                    }

                    // 2. Read Request Body
                    return extractBodyBytes(request)
                            .flatMap(bodyBytes -> {
                                HttpMethod method = request.getMethod();
                                String query = request.getURI().getRawQuery();
                                HttpHeaders headers = request.getHeaders();

                                // 3. Process via Idempotency Engine
                                return idempotencyEngine.handle(method, path, query, headers, bodyBytes)
                                        .flatMap(cachedResponse -> writeToClientResponse(response, cachedResponse));
                            });
                })
                .doFinally(signalType -> metrics.recordProxyLatency(System.nanoTime() - startNanos));
    }

    private Mono<byte[]> extractBodyBytes(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0]);
    }

    private void applyRateLimitHeaders(ServerHttpResponse response, RateLimitResult result) {
        HttpHeaders headers = response.getHeaders();
        headers.set("X-RateLimit-Limit", String.valueOf(result.getLimit()));
        headers.set("X-RateLimit-Remaining", String.valueOf(result.getRemaining()));
        headers.set("X-RateLimit-Reset", String.valueOf(result.getResetSeconds()));
        if (!result.isAllowed()) {
            headers.set("Retry-After", String.valueOf(result.getRetryAfterSeconds()));
        }
    }

    private Mono<Void> writeRateLimitExceededResponse(ServerHttpResponse response, String path, RateLimitResult result) {
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetailDto problem = ProblemDetailDto.of(
                URI.create("https://datatracker.ietf.org/doc/html/rfc6585#section-4"),
                "Too Many Requests",
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Rate limit exceeded. Please retry after " + result.getRetryAfterSeconds() + " seconds.",
                path
        );

        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(problem);
        } catch (JsonProcessingException e) {
            payload = "{\"title\":\"Too Many Requests\"}".getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(payload);
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<Void> writeToClientResponse(ServerHttpResponse response, CachedHttpResponse cachedResponse) {
        response.setStatusCode(HttpStatus.valueOf(cachedResponse.getStatusCode()));

        HttpHeaders targetHeaders = response.getHeaders();
        for (Map.Entry<String, List<String>> entry : cachedResponse.getHeaders().entrySet()) {
            String name = entry.getKey();
            if (!HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                targetHeaders.put(name, entry.getValue());
            }
        }

        byte[] body = cachedResponse.getBody();
        if (body != null && body.length > 0) {
            DataBuffer buffer = response.bufferFactory().wrap(body);
            return response.writeWith(Mono.just(buffer));
        }

        return response.setComplete();
    }
}
