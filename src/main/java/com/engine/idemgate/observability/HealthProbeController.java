package com.engine.idemgate.observability;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/idemgate/health")
public class HealthProbeController {

    @GetMapping("/liveness")
    public Mono<ResponseEntity<Map<String, String>>> liveness() {
        return Mono.just(ResponseEntity.ok(Map.of("status", "UP")));
    }

    @GetMapping("/readiness")
    public Mono<ResponseEntity<Map<String, String>>> readiness() {
        return Mono.just(ResponseEntity.ok(Map.of("status", "UP", "gateway", "READY")));
    }
}
