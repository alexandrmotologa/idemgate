package com.engine.idemgate;

import com.engine.idemgate.config.IdemGateProperties;
import com.engine.idemgate.mock.MockUpstreamController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminFeaturesIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MockUpstreamController mockUpstreamController;

    @Autowired
    private IdemGateProperties properties;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        properties.getUpstream().setUrl("http://localhost:" + port + "/upstream-mock");
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        mockUpstreamController.resetInvocations().block();
    }

    @Test
    @DisplayName("Should serve the web dashboard console at /idemgate/dashboard")
    void shouldServeDashboard() {
        webTestClient.get()
                .uri("/idemgate/dashboard")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(html -> {
                    assertThat(html).contains("IdemGate");
                    assertThat(html).contains("Idempotency Keys Register");
                    assertThat(html).contains("Live Request Simulator");
                });
    }

    @Test
    @DisplayName("Should list keys, return live stats, and manually evict an idempotency key")
    void shouldManageAndEvictKeys() {
        String key = "admin-evict-key-" + System.currentTimeMillis();

        // 1. Create a resolved key via proxy
        webTestClient.post()
                .uri("/api/v1/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"item\": \"WIDGET-1\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "false");

        // 2. Query keys list via admin API
        webTestClient.get()
                .uri("/idemgate/api/v1/keys")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keys").isArray()
                .jsonPath("$.count").isNumber();

        // 3. Query live stats
        webTestClient.get()
                .uri("/idemgate/api/v1/stats")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalRequests").isNumber()
                .jsonPath("$.activeCachedKeys").isNumber();

        // 4. Manually evict the key
        webTestClient.delete()
                .uri("/idemgate/api/v1/keys/" + key)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.key").isEqualTo(key)
                .jsonPath("$.evicted").isEqualTo(true);

        // 5. Verify the key was evicted: sending request with same key should now execute upstream freshly!
        webTestClient.post()
                .uri("/api/v1/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"item\": \"WIDGET-1\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "false"); // Fresh request, NOT replayed!

        // Total upstream invocations should now be 2
        var stats = mockUpstreamController.getInvocationStats().block();
        assertThat(stats).isNotNull();
        assertThat(stats.get("totalInvocations")).isEqualTo(2);
    }

    @Test
    @DisplayName("Should inject telemetry headers including request ID, latency, and W3C traceparent")
    void shouldInjectTelemetryHeaders() {
        webTestClient.post()
                .uri("/api/v1/orders")
                .header("X-Request-Id", "custom-req-trace-42")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ping\": true}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("X-IdemGate-Request-Id", "custom-req-trace-42")
                .expectHeader().exists("X-IdemGate-Latency-Ms")
                .expectHeader().exists("traceparent");
    }

    @Test
    @DisplayName("Should enforce header fingerprinting when includeHeaders is configured")
    void shouldEnforceHeaderFingerprinting() {
        // Configure include-headers to include X-Tenant-ID
        properties.getIdempotency().getFingerprint().setIncludeHeaders(List.of("X-Tenant-ID"));

        String key = "header-fp-key-" + System.currentTimeMillis();
        String payload = "{\"orderId\": \"ORD-500\"}";

        // 1. First request with tenant-1
        webTestClient.post()
                .uri("/api/v1/orders")
                .header("Idempotency-Key", key)
                .header("X-Tenant-ID", "tenant-alpha")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "false");

        // 2. Second request with same payload and key, but DIFFERENT X-Tenant-ID -> 422 Mismatch!
        webTestClient.post()
                .uri("/api/v1/orders")
                .header("Idempotency-Key", key)
                .header("X-Tenant-ID", "tenant-beta") // Altered header!
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Idempotency Key Payload Mismatch");

        // Reset include-headers
        properties.getIdempotency().getFingerprint().setIncludeHeaders(List.of());
    }

    @Test
    @DisplayName("Should enforce granular route-specific rate limiting")
    void shouldEnforceGranularRouteRateLimiting() {
        // Set up route rule for /api/v1/payments/** with capacity 2
        IdemGateProperties.RouteRateLimitRule paymentRule = new IdemGateProperties.RouteRateLimitRule();
        paymentRule.setPath("/api/v1/payments/**");
        paymentRule.setMethod("POST");
        paymentRule.setCapacity(2);
        paymentRule.setRefillRate(1);

        properties.getRateLimit().setRouteRules(List.of(paymentRule));

        String tenant = "payment-rate-tester";

        // 1st request -> OK
        webTestClient.post()
                .uri("/api/v1/payments/pay")
                .header("X-API-Key", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount\": 10}")
                .exchange()
                .expectStatus().isCreated();

        // 2nd request -> OK
        webTestClient.post()
                .uri("/api/v1/payments/pay")
                .header("X-API-Key", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount\": 20}")
                .exchange()
                .expectStatus().isCreated();

        // 3rd request -> Throttled 429!
        webTestClient.post()
                .uri("/api/v1/payments/pay")
                .header("X-API-Key", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount\": 30}")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists("Retry-After");

        // Clean up
        properties.getRateLimit().setRouteRules(List.of());
    }
}
