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
class PayloadMismatchIntegrationTest {

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
    @DisplayName("Should return 422 Unprocessable Entity when idempotency key is reused with altered payload")
    void shouldReturn422WhenPayloadMismatches() {
        String key = "mismatch-test-key-" + System.currentTimeMillis();
        String payload1 = "{\"itemId\": \"ITEM-A\", \"quantity\": 1}";
        String payload2 = "{\"itemId\": \"ITEM-B\", \"quantity\": 99}";

        // 1. First request with payload 1 -> 201 Created
        webTestClient.post()
                .uri("/api/v1/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload1)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "false");

        // 2. Second request with same key but payload 2 -> 422 Unprocessable Entity
        webTestClient.post()
                .uri("/api/v1/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload2)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(422)
                .jsonPath("$.title").isEqualTo("Idempotency Key Payload Mismatch")
                .jsonPath("$.detail").value(val -> assertThat(val.toString()).contains("different request payload"));

        // 3. Third request with same key and original payload 1 -> 201 Created (replayed from cache)
        webTestClient.post()
                .uri("/api/v1/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload1)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "true")
                .expectHeader().exists("Original-Date");

        // Verify upstream was only called ONCE throughout the 3 requests!
        var stats = mockUpstreamController.getInvocationStats().block();
        assertThat(stats).isNotNull();
        assertThat(stats.get("totalInvocations")).isEqualTo(1);
    }
}
