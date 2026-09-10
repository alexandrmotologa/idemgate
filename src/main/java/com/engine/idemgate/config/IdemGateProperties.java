package com.engine.idemgate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "idemgate")
public class IdemGateProperties {

    private Upstream upstream = new Upstream();
    private Storage storage = new Storage();
    private Idempotency idempotency = new Idempotency();
    private RateLimit rateLimit = new RateLimit();

    public Upstream getUpstream() {
        return upstream;
    }

    public void setUpstream(Upstream upstream) {
        this.upstream = upstream;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public void setIdempotency(Idempotency idempotency) {
        this.idempotency = idempotency;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public static class Upstream {
        private String url = "http://localhost:8080/upstream-mock";
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 30000;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    public static class Storage {
        private String type = "memory"; // "memory" or "redis"

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public boolean isRedis() {
            return "redis".equalsIgnoreCase(type);
        }
    }

    public static class Idempotency {
        private boolean enabled = true;
        private String headerName = "Idempotency-Key";
        private int lockTtlSeconds = 30;
        private int recordTtlSeconds = 86400;
        private long maxBodySizeBytes = 10485760; // 10 MB
        private int waitTimeoutSeconds = 30;
        private List<RouteRule> routes = new ArrayList<>();
        private Fingerprint fingerprint = new Fingerprint();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }

        public int getLockTtlSeconds() {
            return lockTtlSeconds;
        }

        public void setLockTtlSeconds(int lockTtlSeconds) {
            this.lockTtlSeconds = lockTtlSeconds;
        }

        public int getRecordTtlSeconds() {
            return recordTtlSeconds;
        }

        public void setRecordTtlSeconds(int recordTtlSeconds) {
            this.recordTtlSeconds = recordTtlSeconds;
        }

        public long getMaxBodySizeBytes() {
            return maxBodySizeBytes;
        }

        public void setMaxBodySizeBytes(long maxBodySizeBytes) {
            this.maxBodySizeBytes = maxBodySizeBytes;
        }

        public int getWaitTimeoutSeconds() {
            return waitTimeoutSeconds;
        }

        public void setWaitTimeoutSeconds(int waitTimeoutSeconds) {
            this.waitTimeoutSeconds = waitTimeoutSeconds;
        }

        public List<RouteRule> getRoutes() {
            return routes;
        }

        public void setRoutes(List<RouteRule> routes) {
            this.routes = routes;
        }

        public Fingerprint getFingerprint() {
            return fingerprint;
        }

        public void setFingerprint(Fingerprint fingerprint) {
            this.fingerprint = fingerprint;
        }
    }

    public static class Fingerprint {
        private List<String> includeHeaders = new ArrayList<>();

        public List<String> getIncludeHeaders() {
            return includeHeaders;
        }

        public void setIncludeHeaders(List<String> includeHeaders) {
            this.includeHeaders = includeHeaders;
        }
    }

    public static class RouteRule {
        private String path = "/**";
        private boolean required = false;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private String tenantHeader = "X-API-Key";
        private long defaultCapacity = 100;
        private long defaultRefillRate = 20;
        private Map<String, TierRule> tiers = new HashMap<>();
        private List<RouteRateLimitRule> routeRules = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTenantHeader() {
            return tenantHeader;
        }

        public void setTenantHeader(String tenantHeader) {
            this.tenantHeader = tenantHeader;
        }

        public long getDefaultCapacity() {
            return defaultCapacity;
        }

        public void setDefaultCapacity(long defaultCapacity) {
            this.defaultCapacity = defaultCapacity;
        }

        public long getDefaultRefillRate() {
            return defaultRefillRate;
        }

        public void setDefaultRefillRate(long defaultRefillRate) {
            this.defaultRefillRate = defaultRefillRate;
        }

        public Map<String, TierRule> getTiers() {
            return tiers;
        }

        public void setTiers(Map<String, TierRule> tiers) {
            this.tiers = tiers;
        }

        public List<RouteRateLimitRule> getRouteRules() {
            return routeRules;
        }

        public void setRouteRules(List<RouteRateLimitRule> routeRules) {
            this.routeRules = routeRules;
        }
    }

    public static class RouteRateLimitRule {
        private String path = "/**";
        private String method = "*";
        private long capacity = 50;
        private long refillRate = 10;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public long getRefillRate() {
            return refillRate;
        }

        public void setRefillRate(long refillRate) {
            this.refillRate = refillRate;
        }
    }

    public static class TierRule {
        private long capacity = 100;
        private long refillRate = 20;

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public long getRefillRate() {
            return refillRate;
        }

        public void setRefillRate(long refillRate) {
            this.refillRate = refillRate;
        }
    }
}
