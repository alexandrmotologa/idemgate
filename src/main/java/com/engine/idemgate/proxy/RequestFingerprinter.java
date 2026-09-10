package com.engine.idemgate.proxy;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes deterministic cryptographic SHA-256 digests of incoming HTTP requests.
 * Per IETF draft section 2.7, request fingerprinting encompasses method, target URI,
 * query parameters, and payload body bytes.
 */
@Component
public class RequestFingerprinter {

    private static final String DIGEST_ALGORITHM = "SHA-256";

    /**
     * Computes the SHA-256 digest of the request components without header hashing.
     */
    public String computeFingerprint(HttpMethod method, String path, String query, byte[] body) {
        return computeFingerprint(method, path, query, body, null, null);
    }

    /**
     * Computes the SHA-256 digest of request method, path, query, body, and specified whitelisted headers.
     *
     * @param method HTTP method (e.g. POST)
     * @param path Normalized request path (e.g. /api/v1/orders)
     * @param query Raw query string (or null)
     * @param body Raw request body bytes (or null)
     * @param headers Incoming HTTP headers (or null)
     * @param includeHeaders Whitelisted header names to incorporate into the fingerprint
     * @return Hexadecimal SHA-256 string
     */
    public String computeFingerprint(
            HttpMethod method,
            String path,
            String query,
            byte[] body,
            org.springframework.http.HttpHeaders headers,
            java.util.List<String> includeHeaders) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);

            // 1. Method
            String methodStr = (method != null ? method.name() : "UNKNOWN") + "\n";
            digest.update(methodStr.getBytes(StandardCharsets.UTF_8));

            // 2. Normalized Path
            String pathStr = (path != null ? path : "/") + "\n";
            digest.update(pathStr.getBytes(StandardCharsets.UTF_8));

            // 3. Query string
            if (query != null && !query.isBlank()) {
                digest.update(query.getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) '\n');

            // 4. Included Headers (sorted canonically)
            if (headers != null && includeHeaders != null && !includeHeaders.isEmpty()) {
                java.util.List<String> sortedHeaders = new java.util.ArrayList<>(includeHeaders);
                sortedHeaders.sort(String.CASE_INSENSITIVE_ORDER);

                for (String headerName : sortedHeaders) {
                    java.util.List<String> values = headers.get(headerName);
                    if (values != null && !values.isEmpty()) {
                        String headerLine = headerName.toLowerCase(java.util.Locale.ROOT) + ":" + String.join(",", values) + "\n";
                        digest.update(headerLine.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            digest.update((byte) '\n');

            // 5. Body bytes
            if (body != null && body.length > 0) {
                digest.update(body);
            }

            byte[] hashBytes = digest.digest();
            return HexFormat.of().formatHex(hashBytes);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in current JVM", e);
        }
    }
}
