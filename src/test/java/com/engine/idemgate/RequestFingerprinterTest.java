package com.engine.idemgate;

import com.engine.idemgate.proxy.RequestFingerprinter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprinterTest {

    private RequestFingerprinter fingerprinter;

    @BeforeEach
    void setUp() {
        fingerprinter = new RequestFingerprinter();
    }

    @Test
    @DisplayName("Should generate identical hash for identical request parameters")
    void shouldGenerateIdenticalHash() {
        byte[] body = "{\"orderId\": \"1001\", \"amount\": 99.99}".getBytes(StandardCharsets.UTF_8);

        String hash1 = fingerprinter.computeFingerprint(HttpMethod.POST, "/api/v1/orders", "trace=true", body);
        String hash2 = fingerprinter.computeFingerprint(HttpMethod.POST, "/api/v1/orders", "trace=true", body);

        assertThat(hash1).isNotNull().isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // 256 bits = 64 hex characters
    }

    @Test
    @DisplayName("Should produce different hash when payload changes")
    void shouldProduceDifferentHashWhenPayloadChanges() {
        byte[] body1 = "{\"orderId\": \"1001\", \"amount\": 99.99}".getBytes(StandardCharsets.UTF_8);
        byte[] body2 = "{\"orderId\": \"1001\", \"amount\": 100.00}".getBytes(StandardCharsets.UTF_8);

        String hash1 = fingerprinter.computeFingerprint(HttpMethod.POST, "/api/v1/orders", null, body1);
        String hash2 = fingerprinter.computeFingerprint(HttpMethod.POST, "/api/v1/orders", null, body2);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("Should produce different hash when method or path changes")
    void shouldProduceDifferentHashWhenMethodOrPathChanges() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        String hashPost = fingerprinter.computeFingerprint(HttpMethod.POST, "/api/v1/orders", null, body);
        String hashPut = fingerprinter.computeFingerprint(HttpMethod.PUT, "/api/v1/orders", null, body);
        String hashDifferentPath = fingerprinter.computeFingerprint(HttpMethod.POST, "/api/v1/payments", null, body);

        assertThat(hashPost).isNotEqualTo(hashPut);
        assertThat(hashPost).isNotEqualTo(hashDifferentPath);
    }

    @Test
    @DisplayName("Should handle null and empty bodies safely")
    void shouldHandleNullAndEmptyBodies() {
        String hashNull = fingerprinter.computeFingerprint(HttpMethod.GET, "/api/v1/items", null, null);
        String hashEmpty = fingerprinter.computeFingerprint(HttpMethod.GET, "/api/v1/items", null, new byte[0]);

        assertThat(hashNull).isNotNull().isEqualTo(hashEmpty);
    }
}
