package io.conduktor.connect.otel;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OtlpAuthenticatorFactoryTest {

    private OpenTelemetrySourceConnectorConfig config(Map<String, String> overrides) {
        Map<String, String> props = new HashMap<>(overrides);
        return new OpenTelemetrySourceConnectorConfig(props);
    }

    @Test
    void returnsNullWhenAuthDisabled() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of("otlp.auth.enabled", "false"));

        assertNull(OtlpAuthenticatorFactory.create(cfg));
    }

    @Test
    void buildsAuthenticatorForApiKey() {
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.enabled", "true",
                "otlp.auth.methods", "api-key",
                "otlp.auth.api-key", "secret"));

        OtlpAuthenticator auth = OtlpAuthenticatorFactory.create(cfg);

        assertNotNull(auth);
        assertTrue(auth.authenticate(Map.of("x-api-key", "secret")).isAuthenticated());
    }

    @Test
    void buildsAuthenticatorForOidcWithExplicitJwksUri() {
        // Explicit jwks-uri: JWKSource construction is lazy, no network call at build time.
        OpenTelemetrySourceConnectorConfig cfg = config(Map.of(
                "otlp.auth.enabled", "true",
                "otlp.auth.methods", "oidc",
                "otlp.auth.oidc.issuer", "https://idp.example/realms/otlp",
                "otlp.auth.oidc.jwks-uri", "https://idp.example/jwks"));

        OtlpAuthenticator auth = OtlpAuthenticatorFactory.create(cfg);

        assertNotNull(auth);
    }
}
