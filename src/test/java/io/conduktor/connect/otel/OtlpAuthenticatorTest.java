package io.conduktor.connect.otel;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the transport-agnostic authentication engine.
 */
class OtlpAuthenticatorTest {

    private OpenTelemetrySourceConnectorConfig config(Map<String, String> overrides) {
        Map<String, String> props = new HashMap<>();
        props.put("otlp.auth.enabled", "true");
        props.putAll(overrides);
        return new OpenTelemetrySourceConnectorConfig(props);
    }

    private Map<String, String> headers(String... kv) {
        Map<String, String> h = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            h.put(kv[i], kv[i + 1]);
        }
        return h;
    }

    // ---- API key ----

    @Test
    void acceptsRequestWithValidApiKey() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.methods", "api-key",
                "otlp.auth.api-key", "secret"));
        OtlpAuthenticator auth = new OtlpAuthenticator(cfg, null);

        OtlpAuthenticator.Result result = auth.authenticate(headers("x-api-key", "secret"));

        assertTrue(result.isAuthenticated());
    }

    @Test
    void rejectsRequestWithMissingApiKey() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.methods", "api-key",
                "otlp.auth.api-key", "secret"));
        OtlpAuthenticator auth = new OtlpAuthenticator(cfg, null);

        OtlpAuthenticator.Result result = auth.authenticate(headers());

        assertFalse(result.isAuthenticated());
        assertNotNull(result.getFailureReason());
    }

    @Test
    void rejectsRequestWithWrongApiKey() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.methods", "api-key",
                "otlp.auth.api-key", "secret"));
        OtlpAuthenticator auth = new OtlpAuthenticator(cfg, null);

        OtlpAuthenticator.Result result = auth.authenticate(headers("x-api-key", "wrong"));

        assertFalse(result.isAuthenticated());
    }

    @Test
    void usesCustomApiKeyHeaderName() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.methods", "api-key",
                "otlp.auth.api-key", "secret",
                "otlp.auth.api-key.header", "x-otlp-key"));
        OtlpAuthenticator auth = new OtlpAuthenticator(cfg, null);

        assertTrue(auth.authenticate(headers("x-otlp-key", "secret")).isAuthenticated());
        assertFalse(auth.authenticate(headers("x-api-key", "secret")).isAuthenticated());
    }

    @Test
    void acceptsAnyOfMultipleConfiguredApiKeys() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.methods", "api-key",
                "otlp.auth.api-key", "key1, key2 ,key3"));
        OtlpAuthenticator auth = new OtlpAuthenticator(cfg, null);

        assertTrue(auth.authenticate(headers("x-api-key", "key2")).isAuthenticated());
        assertTrue(auth.authenticate(headers("x-api-key", "key3")).isAuthenticated());
        assertFalse(auth.authenticate(headers("x-api-key", "key4")).isAuthenticated());
    }

    @Test
    void headerLookupIsCaseInsensitive() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.methods", "api-key",
                "otlp.auth.api-key", "secret"));
        OtlpAuthenticator auth = new OtlpAuthenticator(cfg, null);

        assertTrue(auth.authenticate(headers("X-API-KEY", "secret")).isAuthenticated());
    }

    // ---- OIDC (bearer) via injected validator ----

    @Test
    void acceptsValidBearerToken() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.methods", "oidc",
                "otlp.auth.oidc.issuer", "https://idp.example"));
        OidcTokenValidator validator = token -> {
            if ("good-token".equals(token)) {
                return "user-123";
            }
            throw new OidcTokenValidator.ValidationException("bad token");
        };
        OtlpAuthenticator auth = new OtlpAuthenticator(cfg, validator);

        OtlpAuthenticator.Result result = auth.authenticate(headers("authorization", "Bearer good-token"));

        assertTrue(result.isAuthenticated());
        assertEquals("user-123", result.getPrincipal());
    }

    @Test
    void rejectsInvalidBearerToken() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.methods", "oidc",
                "otlp.auth.oidc.issuer", "https://idp.example"));
        OidcTokenValidator validator = token -> {
            throw new OidcTokenValidator.ValidationException("expired");
        };
        OtlpAuthenticator auth = new OtlpAuthenticator(cfg, validator);

        assertFalse(auth.authenticate(headers("authorization", "Bearer whatever")).isAuthenticated());
    }

    @Test
    void rejectsMissingBearerToken() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.methods", "oidc",
                "otlp.auth.oidc.issuer", "https://idp.example"));
        OtlpAuthenticator auth = new OtlpAuthenticator(cfg, token -> "x");

        assertFalse(auth.authenticate(headers()).isAuthenticated());
    }

    @Test
    void rejectsMalformedAuthorizationHeader() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.methods", "oidc",
                "otlp.auth.oidc.issuer", "https://idp.example"));
        OtlpAuthenticator auth = new OtlpAuthenticator(cfg, token -> "x");

        assertFalse(auth.authenticate(headers("authorization", "Basic abc")).isAuthenticated());
    }

    // ---- combined ----

    @Test
    void acceptsEitherMethodWhenBothEnabled() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.methods", "api-key,oidc",
                "otlp.auth.api-key", "secret",
                "otlp.auth.oidc.issuer", "https://idp.example"));
        OidcTokenValidator validator = token -> "good-token".equals(token) ? "u" : fail(token);
        OtlpAuthenticator auth = new OtlpAuthenticator(cfg, validator);

        assertTrue(auth.authenticate(headers("x-api-key", "secret")).isAuthenticated());
        assertTrue(auth.authenticate(headers("authorization", "Bearer good-token")).isAuthenticated());
        assertFalse(auth.authenticate(headers("x-api-key", "nope")).isAuthenticated());
    }

    private static String fail(String token) {
        throw new OidcTokenValidator.ValidationException("bad: " + token);
    }
}
