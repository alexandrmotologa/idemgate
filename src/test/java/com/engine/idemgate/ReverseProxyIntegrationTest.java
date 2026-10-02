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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReverseProxyIntegrationTest {

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
    @DisplayName("Should forward non-idempotent requests directly to upstream")
    void shouldForwardNonIdempotentRequests() {
        webTestClient.post()
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"event\": \"USER_LOGIN\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().doesNotExist("Idempotent-Replayed")
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCESS");

        var stats = mockUpstreamController.getInvocationStats().block();
        assertThat(stats).isNotNull();
        assertThat(stats.get("totalInvocations")).isEqualTo(1);
    }

    @Test
    @DisplayName("Should forward safe methods (GET) without acquiring idempotency locks")
    void shouldForwardSafeMethodsWithoutLocking() {
        String key = "safe-get-key-" + System.currentTimeMillis();

        webTestClient.get()
                .uri("/api/v1/items/123")
                .header("Idempotency-Key", key)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().doesNotExist("Idempotent-Replayed");

        // Inspect key - should not exist in storage because safe methods bypass idempotency state
        webTestClient.get()
                .uri("/idemgate/api/v1/inspect/" + key)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Should reject requests with invalid characters (spaces) in Idempotency-Key with 400 Bad Request")
    void shouldRejectInvalidKeyCharacters() {
        String invalidKey = "invalid key with spaces";

        webTestClient.post()
                .uri("/api/v1/orders")
                .header("Idempotency-Key", invalidKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderId\": \"INV-1\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.title").isEqualTo("Invalid Idempotency-Key Format");
    }

    @Test
    @DisplayName("Should not cache transient 5xx server errors as RESOLVED and allow retries")
    void shouldNotCacheTransient5xxErrors() {
        String key = "5xx-retry-key-" + System.currentTimeMillis();

        // 1. Request upstream with simulated 500 error
        webTestClient.post()
                .uri("/api/v1/orders")
                .header("Idempotency-Key", key)
                .header("X-Mock-Status", "500")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderId\": \"FAIL-500\"}")
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectHeader().valueEquals("Idempotent-Replayed", "false");

        // 2. Key must NOT be in cache as RESOLVED
        webTestClient.get()
                .uri("/idemgate/api/v1/inspect/" + key)
                .exchange()
                .expectStatus().isNotFound();

        // 3. Retry request with 201 status -> should reach upstream again and succeed
        webTestClient.post()
                .uri("/api/v1/orders")
                .header("Idempotency-Key", key)
                .header("X-Mock-Status", "201")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderId\": \"FAIL-500\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "false");

        // Upstream should have been called twice (once for 500, once for 201)
        var stats = mockUpstreamController.getInvocationStats().block();
        assertThat(stats).isNotNull();
        assertThat(stats.get("totalInvocations")).isEqualTo(2);
    }

    @Test
    @DisplayName("Should return health status on liveness and readiness probes")
    void shouldReturnHealthStatus() {
        webTestClient.get()
                .uri("/idemgate/health/liveness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");

        webTestClient.get()
                .uri("/idemgate/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Should allow inspection of resolved idempotency keys")
    void shouldInspectResolvedKey() {
        String key = "inspect-test-key-" + System.currentTimeMillis();

        // Populate key via proxy request
        webTestClient.post()
                .uri("/api/v1/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"orderId\": \"INSPECT-1\"}")
                .exchange()
                .expectStatus().isCreated();

        // Inspect key via operator inspection endpoint
        webTestClient.get()
                .uri("/idemgate/api/v1/inspect/" + key)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.key").isEqualTo(key)
                .jsonPath("$.status").isEqualTo("RESOLVED")
                .jsonPath("$.hasCachedResponse").isEqualTo(true)
                .jsonPath("$.cachedStatusCode").isEqualTo(201);
    }

    @Test
    @DisplayName("Should expose Prometheus metrics")
    void shouldExposePrometheusMetrics() {
        webTestClient.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("idemgate_requests_total"));
    }
}
