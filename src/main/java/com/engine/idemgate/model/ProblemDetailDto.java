package com.engine.idemgate.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;
import java.time.Instant;

/**
 * Standard RFC 7807 Problem Details representation for HTTP error responses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProblemDetailDto {

    private final URI type;
    private final String title;
    private final int status;
    private final String detail;
    private final String instance;
    private final Instant timestamp;

    public ProblemDetailDto(URI type, String title, int status, String detail, String instance) {
        this.type = type;
        this.title = title;
        this.status = status;
        this.detail = detail;
        this.instance = instance;
        this.timestamp = Instant.now();
    }

    public static ProblemDetailDto of(URI type, String title, int status, String detail, String instance) {
        return new ProblemDetailDto(type, title, status, detail, instance);
    }

    public URI getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public int getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public String getInstance() {
        return instance;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
