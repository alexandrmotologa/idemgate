package com.engine.idemgate.ratelimit;

import com.engine.idemgate.config.IdemGateProperties;
import com.engine.idemgate.model.RateLimitResult;
import com.engine.idemgate.observability.IdemMetrics;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Evaluates tenant tiers and enforces rate limiting rules against incoming requests.
 */
@Service
public class RateLimiterService {

    private final RateLimiterBackend backend;
    private final IdemGateProperties properties;
    private final TenantContextResolver tenantResolver;
    private final IdemMetrics metrics;

    public RateLimiterService(
            RateLimiterBackend backend,
            IdemGateProperties properties,
            TenantContextResolver tenantResolver,
            IdemMetrics metrics) {
        this.backend = backend;
        this.properties = properties;
        this.tenantResolver = tenantResolver;
        this.metrics = metrics;
    }

    public Mono<RateLimitResult> checkRateLimit(ServerHttpRequest request) {
        if (!properties.getRateLimit().isEnabled()) {
            return Mono.just(RateLimitResult.allow(Long.MAX_VALUE, Long.MAX_VALUE, 0));
        }

        String tenantId = tenantResolver.resolveTenantId(request);
        long capacity = properties.getRateLimit().getDefaultCapacity();
        long refillRate = properties.getRateLimit().getDefaultRefillRate();

        // Check if tenant matches any configured tier (e.g. apikey:free-123 or apikey:pro-456)
        Map<String, IdemGateProperties.TierRule> tiers = properties.getRateLimit().getTiers();
        for (Map.Entry<String, IdemGateProperties.TierRule> entry : tiers.entrySet()) {
            if (tenantId.toLowerCase().contains(entry.getKey().toLowerCase())) {
                capacity = entry.getValue().getCapacity();
                refillRate = entry.getValue().getRefillRate();
                break;
            }
        }

        return backend.tryAcquire(tenantId, capacity, refillRate, 1)
                .doOnNext(result -> {
                    if (!result.isAllowed()) {
                        metrics.incrementRateLimited();
                    }
                });
    }
}
