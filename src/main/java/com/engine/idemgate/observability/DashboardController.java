package com.engine.idemgate.observability;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Serves the embedded web dashboard console for operators and developers.
 */
@RestController
@RequestMapping("/idemgate/dashboard")
public class DashboardController {

    private final String dashboardHtml;

    public DashboardController() {
        String html;
        try {
            ClassPathResource resource = new ClassPathResource("static/dashboard.html");
            html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            html = "<html><body><h1>Dashboard load error</h1></body></html>";
        }
        this.dashboardHtml = html;
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<String>> getDashboard() {
        return Mono.just(ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(dashboardHtml));
    }
}
