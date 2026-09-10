package com.engine.idemgate;

import com.engine.idemgate.mock.MockUpstreamController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConcurrentRaceIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MockUpstreamController mockUpstreamController;

    @Autowired
    private com.engine.idemgate.config.IdemGateProperties properties;

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
    @DisplayName("Should serialize 20 concurrent threads: exactly 1 upstream execution, 100% matching responses")
    void shouldSerializeConcurrentDuplicates() throws Exception {
        int threadCount = 20;
        String idempotencyKey = "concurrent-race-key-" + System.currentTimeMillis();
        String payload = "{\"accountId\": \"ACC-123\", \"amount\": 450.00, \"currency\": \"USD\"}";

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<ResponseRecord>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                // Wait for all threads to be ready to maximize race condition probability
                startLatch.await();

                EntityExchangeResult<byte[]> result = webTestClient.post()
                        .uri("/api/v1/charges")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-API-Key", "tenant-unlimited-test")
                        .header("X-Mock-Delay", "250") // 250ms delay upstream
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(payload)
                        .exchange()
                        .expectStatus().isCreated()
                        .expectBody()
                        .returnResult();

                int status = result.getStatus().value();
                String replayedHeader = result.getResponseHeaders().getFirst("Idempotent-Replayed");
                String responseBody = new String(result.getResponseBody());

                return new ResponseRecord(status, replayedHeader, responseBody);
            }));
        }

        // Wait until all threads are poised at startLatch
        readyLatch.await(5, TimeUnit.SECONDS);
        // Release all threads simultaneously
        startLatch.countDown();

        AtomicInteger primaryCount = new AtomicInteger(0);
        AtomicInteger replayedCount = new AtomicInteger(0);
        String canonicalResponseBody = null;

        for (Future<ResponseRecord> future : futures) {
            ResponseRecord record = future.get(15, TimeUnit.SECONDS);

            assertThat(record.status()).isEqualTo(201);
            assertThat(record.body()).isNotBlank();

            if (canonicalResponseBody == null) {
                canonicalResponseBody = record.body();
            } else {
                // All 20 threads must receive the EXACT identical response body!
                assertThat(record.body()).isEqualTo(canonicalResponseBody);
            }

            if ("false".equalsIgnoreCase(record.replayedHeader())) {
                primaryCount.incrementAndGet();
            } else if ("true".equalsIgnoreCase(record.replayedHeader())) {
                replayedCount.incrementAndGet();
            }
        }

        executor.shutdown();

        // Verification 1: Exactly 1 primary caller, 19 replayed callers
        assertThat(primaryCount.get()).isEqualTo(1);
        assertThat(replayedCount.get()).isEqualTo(threadCount - 1);

        // Verification 2: Upstream mock service was invoked EXACTLY ONCE!
        var stats = mockUpstreamController.getInvocationStats().block();
        assertThat(stats).isNotNull();
        assertThat(stats.get("totalInvocations")).isEqualTo(1);
    }

    private record ResponseRecord(int status, String replayedHeader, String body) {}
}
