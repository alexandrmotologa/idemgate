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
    private final org.springframework.util.AntPathMatcher pathMatcher = new org.springframework.util.AntPathMatcher();

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

        String baseTenantId = tenantResolver.resolveTenantId(request);
        String path = request.getURI().getPath();
        String method = request.getMethod() != null ? request.getMethod().name() : "*";

        long capacity = properties.getRateLimit().getDefaultCapacity();
        long refillRate = properties.getRateLimit().getDefaultRefillRate();
        String bucketKey = baseTenantId;

        // 1. Check granular route rules first
        boolean matchedRoute = false;
        for (IdemGateProperties.RouteRateLimitRule rule : properties.getRateLimit().getRouteRules()) {
            boolean pathMatches = pathMatcher.match(rule.getPath(), path);
            boolean methodMatches = "*".equals(rule.getMethod()) || rule.getMethod().equalsIgnoreCase(method);

            if (pathMatches && methodMatches) {
                capacity = rule.getCapacity();
                refillRate = rule.getRefillRate();
                bucketKey = baseTenantId + ":" + rule.getPath();
                matchedRoute = true;
                break;
            }
        }

        // 2. Fall back to tenant tiers if no specific route rule matched
        if (!matchedRoute) {
            Map<String, IdemGateProperties.TierRule> tiers = properties.getRateLimit().getTiers();
            for (Map.Entry<String, IdemGateProperties.TierRule> entry : tiers.entrySet()) {
                if (baseTenantId.toLowerCase().contains(entry.getKey().toLowerCase())) {
                    capacity = entry.getValue().getCapacity();
                    refillRate = entry.getValue().getRefillRate();
                    break;
                }
            }
        }

        return backend.tryAcquire(bucketKey, capacity, refillRate, 1)
                .doOnNext(result -> {
                    if (!result.isAllowed()) {
                        metrics.incrementRateLimited();
                    }
                });
    }
}
