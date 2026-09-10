package com.engine.idemgate.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates a cached HTTP response, including status, headers, body payload, and timestamp.
 */
public class CachedHttpResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final Instant createdAt;

    @JsonCreator
    public CachedHttpResponse(
            @JsonProperty("statusCode") int statusCode,
            @JsonProperty("headers") Map<String, List<String>> headers,
            @JsonProperty("body") byte[] body,
            @JsonProperty("createdAt") Instant createdAt) {
        this.statusCode = statusCode;
        this.headers = headers != null ? Collections.unmodifiableMap(headers) : Collections.emptyMap();
        this.body = body != null ? body : new byte[0];
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
