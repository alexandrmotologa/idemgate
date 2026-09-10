package com.engine.idemgate.proxy;

import com.engine.idemgate.config.IdemGateProperties;
import com.engine.idemgate.model.CachedHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.*;

/**
 * Dispatches requests to the target upstream microservice while preserving
 * methods, headers, query parameters, and payload bodies.
 */
@Component
public class UpstreamDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UpstreamDispatcher.class);

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailers",
            "transfer-encoding",
            "upgrade",
            "host"
    );

    private final WebClient webClient;
    private final IdemGateProperties properties;

    public UpstreamDispatcher(WebClient webClient, IdemGateProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    /**
     * Forwards an incoming request to the configured upstream base URL.
     *
     * @param method HTTP method
     * @param path target path
     * @param query query string
     * @param incomingHeaders headers from client
     * @param body request body bytes
     * @return Mono of CachedHttpResponse
     */
    public Mono<CachedHttpResponse> forward(
            HttpMethod method,
            String path,
            String query,
            HttpHeaders incomingHeaders,
            byte[] body) {

        String baseUri = properties.getUpstream().getUrl();
        StringBuilder targetUriBuilder = new StringBuilder(baseUri);
        if (!baseUri.endsWith("/") && !path.startsWith("/")) {
            targetUriBuilder.append("/");
        }
        targetUriBuilder.append(path);
        if (query != null && !query.isBlank()) {
            targetUriBuilder.append("?").append(query);
        }

        URI targetUri = URI.create(targetUriBuilder.toString());
        log.debug("Forwarding {} request to upstream: {}", method, targetUri);

        WebClient.RequestBodySpec requestSpec = webClient.method(method)
                .uri(targetUri)
                .headers(headers -> copyForwardHeaders(incomingHeaders, headers));

        if (body != null && body.length > 0) {
            MediaType contentType = incomingHeaders.getContentType();
            if (contentType != null) {
                requestSpec.contentType(contentType);
            }
            requestSpec.body(BodyInserters.fromValue(body));
        }

        return requestSpec.exchangeToMono(this::buildCachedResponse);
    }

    private void copyForwardHeaders(HttpHeaders source, HttpHeaders target) {
        source.forEach((headerName, headerValues) -> {
            if (!HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase(Locale.ROOT))) {
                target.addAll(headerName, headerValues);
            }
        });
    }

    private Mono<CachedHttpResponse> buildCachedResponse(ClientResponse clientResponse) {
        int statusCode = clientResponse.statusCode().value();

        Map<String, List<String>> responseHeaders = new HashMap<>();
        clientResponse.headers().asHttpHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                responseHeaders.put(name, new ArrayList<>(values));
            }
        });

        return clientResponse.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .map(bodyBytes -> new CachedHttpResponse(statusCode, responseHeaders, bodyBytes, Instant.now()));
    }
}
