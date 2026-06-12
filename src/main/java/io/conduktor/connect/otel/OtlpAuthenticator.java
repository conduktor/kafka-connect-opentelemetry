package io.conduktor.connect.otel;

import org.apache.kafka.common.config.types.Password;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Transport-agnostic authentication engine shared by the gRPC and HTTP OTLP receivers.
 *
 * <p>A request is authenticated if it satisfies any one of the configured methods
 * (api-key, oidc). Header lookups are case-insensitive so the same logic works for both
 * HTTP headers and gRPC metadata.
 */
public class OtlpAuthenticator {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORIZATION_HEADER = "authorization";

    private final boolean apiKeyEnabled;
    private final String apiKeyHeader;
    private final List<String> validApiKeys;

    private final boolean oidcEnabled;
    private final OidcTokenValidator oidcValidator;

    public OtlpAuthenticator(OpenTelemetrySourceConnectorConfig config, OidcTokenValidator oidcValidator) {
        this.apiKeyEnabled = config.isApiKeyAuthEnabled();
        this.apiKeyHeader = config.getApiKeyHeader();
        this.validApiKeys = parseApiKeys(config.getApiKey());

        this.oidcEnabled = config.isOidcAuthEnabled();
        this.oidcValidator = oidcValidator;

        if (oidcEnabled && oidcValidator == null) {
            throw new IllegalArgumentException("OIDC authentication enabled but no token validator was provided");
        }
    }

    private static List<String> parseApiKeys(Password apiKey) {
        if (apiKey == null || apiKey.value() == null) {
            return Collections.emptyList();
        }
        List<String> keys = new ArrayList<>();
        for (String key : apiKey.value().split(",")) {
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) {
                keys.add(trimmed);
            }
        }
        return Collections.unmodifiableList(keys);
    }

    /**
     * Authenticate a request from its headers (HTTP) or metadata (gRPC).
     */
    public Result authenticate(Map<String, String> headers) {
        Map<String, String> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            ci.putAll(headers);
        }

        List<String> failures = new ArrayList<>();

        if (apiKeyEnabled) {
            String reason = tryApiKey(ci);
            if (reason == null) {
                return Result.success("api-key");
            }
            failures.add("api-key: " + reason);
        }

        if (oidcEnabled) {
            ApiResult result = tryOidc(ci);
            if (result.principal != null) {
                return Result.success(result.principal);
            }
            failures.add("oidc: " + result.reason);
        }

        return Result.failure(String.join("; ", failures));
    }

    /** @return null on success, otherwise a failure reason. */
    private String tryApiKey(Map<String, String> headers) {
        String presented = headers.get(apiKeyHeader);
        if (presented == null || presented.isEmpty()) {
            return "missing key header '" + apiKeyHeader + "'";
        }
        for (String valid : validApiKeys) {
            if (constantTimeEquals(presented, valid)) {
                return null;
            }
        }
        return "invalid api key";
    }

    private ApiResult tryOidc(Map<String, String> headers) {
        String authorization = headers.get(AUTHORIZATION_HEADER);
        if (authorization == null || authorization.isEmpty()) {
            return ApiResult.fail("missing Authorization header");
        }
        if (authorization.length() <= BEARER_PREFIX.length()
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return ApiResult.fail("Authorization header is not a Bearer token");
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        try {
            String principal = oidcValidator.validate(token);
            return ApiResult.ok(principal != null ? principal : "oidc");
        } catch (OidcTokenValidator.ValidationException e) {
            return ApiResult.fail(e.getMessage());
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static final class ApiResult {
        final String principal;
        final String reason;

        private ApiResult(String principal, String reason) {
            this.principal = principal;
            this.reason = reason;
        }

        static ApiResult ok(String principal) {
            return new ApiResult(principal, null);
        }

        static ApiResult fail(String reason) {
            return new ApiResult(null, reason);
        }
    }

    /** Outcome of an authentication attempt. */
    public static final class Result {
        private final boolean authenticated;
        private final String principal;
        private final String failureReason;

        private Result(boolean authenticated, String principal, String failureReason) {
            this.authenticated = authenticated;
            this.principal = principal;
            this.failureReason = failureReason;
        }

        static Result success(String principal) {
            return new Result(true, principal, null);
        }

        static Result failure(String reason) {
            return new Result(false, null, reason);
        }

        public boolean isAuthenticated() {
            return authenticated;
        }

        public String getPrincipal() {
            return principal;
        }

        public String getFailureReason() {
            return failureReason;
        }
    }
}
