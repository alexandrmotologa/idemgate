package com.engine.idemgate.ratelimit;

import com.engine.idemgate.config.IdemGateProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * Resolves tenant identity from incoming HTTP requests using configurable headers,
 * authorization tokens, or client IP addresses.
 */
@Component
public class TenantContextResolver {

    private final IdemGateProperties properties;

    public TenantContextResolver(IdemGateProperties properties) {
        this.properties = properties;
    }

    public String resolveTenantId(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String tenantHeader = properties.getRateLimit().getTenantHeader();

        // 1. Configured header (e.g. X-API-Key)
        if (tenantHeader != null && !tenantHeader.isBlank()) {
            String apiKey = headers.getFirst(tenantHeader);
            if (apiKey != null && !apiKey.isBlank()) {
                return "apikey:" + apiKey.trim();
            }
        }

        // 2. Authorization header
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && !authHeader.isBlank()) {
            if (authHeader.startsWith("Bearer ")) {
                return "bearer:" + Integer.toHexString(authHeader.substring(7).hashCode());
            }
            return "auth:" + Integer.toHexString(authHeader.hashCode());
        }

        // 3. X-Forwarded-For header
        String xff = headers.getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String clientIp = xff.split(",")[0].trim();
            return "ip:" + clientIp;
        }

        // 4. Remote IP Address
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return "ip:" + remoteAddress.getAddress().getHostAddress();
        }

        return "anonymous:default";
    }
}
