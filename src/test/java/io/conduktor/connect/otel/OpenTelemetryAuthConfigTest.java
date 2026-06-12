package io.conduktor.connect.otel;

import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OTLP authentication configuration keys, defaults and validation.
 */
class OpenTelemetryAuthConfigTest {

    @Test
    void testAuthDisabledByDefault() {
        OpenTelemetrySourceConnectorConfig config =
                new OpenTelemetrySourceConnectorConfig(new HashMap<>());

        assertFalse(config.isAuthEnabled());
        assertTrue(config.getAuthMethods().isEmpty());
        assertNull(config.getApiKey());
        assertEquals("x-api-key", config.getApiKeyHeader());
        assertEquals("", config.getOidcIssuer());
        assertEquals("", config.getOidcJwksUri());
        assertEquals("", config.getOidcAudience());
    }

    @Test
    void testApiKeyAuthConfiguration() {
        Map<String, String> props = new HashMap<>();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_ENABLED_CONFIG, "true");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_METHODS_CONFIG, "api-key");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_API_KEY_CONFIG, "secret1,secret2");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_API_KEY_HEADER_CONFIG, "x-otlp-key");

        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);

        assertTrue(config.isAuthEnabled());
        assertEquals(List.of("api-key"), config.getAuthMethods());
        assertEquals("secret1,secret2", config.getApiKey().value());
        assertEquals("x-otlp-key", config.getApiKeyHeader());
    }

    @Test
    void testOidcAuthConfiguration() {
        Map<String, String> props = new HashMap<>();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_ENABLED_CONFIG, "true");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_METHODS_CONFIG, "oidc");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_OIDC_ISSUER_CONFIG, "https://idp.example/realms/otlp");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_OIDC_JWKS_URI_CONFIG, "https://idp.example/jwks");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_OIDC_AUDIENCE_CONFIG, "otlp-connector");

        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);

        assertEquals(List.of("oidc"), config.getAuthMethods());
        assertEquals("https://idp.example/realms/otlp", config.getOidcIssuer());
        assertEquals("https://idp.example/jwks", config.getOidcJwksUri());
        assertEquals("otlp-connector", config.getOidcAudience());
    }

    @Test
    void testMultipleAuthMethods() {
        Map<String, String> props = new HashMap<>();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_ENABLED_CONFIG, "true");
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_METHODS_CONFIG, "api-key,oidc");

        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);

        assertEquals(List.of("api-key", "oidc"), config.getAuthMethods());
    }

    @Test
    void testInvalidAuthMethodRejected() {
        Map<String, String> props = new HashMap<>();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_METHODS_CONFIG, "api-key,carrier-pigeon");

        assertThrows(ConfigException.class,
                () -> new OpenTelemetrySourceConnectorConfig(props));
    }

    @Test
    void testAuthMethodsAreCaseInsensitive() {
        Map<String, String> props = new HashMap<>();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_METHODS_CONFIG, "API-KEY, OIDC");

        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);

        assertEquals(List.of("api-key", "oidc"), config.getAuthMethods());
    }

    @Test
    void testApiKeyIsPasswordTypeAndMaskedInToString() {
        Map<String, String> props = new HashMap<>();
        props.put(OpenTelemetrySourceConnectorConfig.OTLP_AUTH_API_KEY_CONFIG, "super-secret-key");

        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);

        // Password.toString() must never leak the secret
        assertEquals("[hidden]", config.getApiKey().toString());
        assertEquals("super-secret-key", config.getApiKey().value());
    }
}
