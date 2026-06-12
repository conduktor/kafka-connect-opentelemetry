package io.conduktor.connect.otel;

/**
 * Builds the {@link OtlpAuthenticator} for a connector configuration, wiring in the
 * OIDC token validator when the 'oidc' method is enabled.
 */
public final class OtlpAuthenticatorFactory {

    private OtlpAuthenticatorFactory() {
    }

    /**
     * @return a configured authenticator, or {@code null} when authentication is disabled.
     */
    public static OtlpAuthenticator create(OpenTelemetrySourceConnectorConfig config) {
        if (!config.isAuthEnabled()) {
            return null;
        }

        OidcTokenValidator oidcValidator = null;
        if (config.isOidcAuthEnabled()) {
            try {
                oidcValidator = NimbusOidcTokenValidator.fromConfig(config);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to initialize OIDC token validator for issuer '"
                                + config.getOidcIssuer() + "': " + e.getMessage(), e);
            }
        }

        return new OtlpAuthenticator(config, oidcValidator);
    }
}
