package io.conduktor.connect.otel;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * OIDC bearer token validator backed by Nimbus JOSE+JWT.
 *
 * <p>Verifies the RS256 signature against the issuer's published JWKS and checks the
 * {@code exp}, {@code iss} and (optionally) {@code aud} claims. The JWKS is fetched
 * remotely and cached/refreshed by {@link JWKSourceBuilder}.
 */
public class NimbusOidcTokenValidator implements OidcTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(NimbusOidcTokenValidator.class);
    private static final String WELL_KNOWN_SUFFIX = "/.well-known/openid-configuration";

    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

    /**
     * Build a validator with an explicit key source. Primarily for testing.
     */
    public NimbusOidcTokenValidator(JWKSource<SecurityContext> keySource, String issuer, String audience) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));

        JWTClaimsSet.Builder exactMatch = new JWTClaimsSet.Builder();
        if (issuer != null && !issuer.trim().isEmpty()) {
            exactMatch.issuer(issuer.trim());
        }
        Set<String> requiredClaims = new HashSet<>(Collections.singletonList("exp"));

        DefaultJWTClaimsVerifier<SecurityContext> verifier;
        if (audience != null && !audience.trim().isEmpty()) {
            verifier = new DefaultJWTClaimsVerifier<>(audience.trim(), exactMatch.build(), requiredClaims);
        } else {
            verifier = new DefaultJWTClaimsVerifier<>(exactMatch.build(), requiredClaims);
        }
        processor.setJWTClaimsSetVerifier(verifier);

        this.jwtProcessor = processor;
    }

    /**
     * Build a validator from connector configuration, fetching the JWKS remotely.
     */
    public static NimbusOidcTokenValidator fromConfig(OpenTelemetrySourceConnectorConfig config) throws Exception {
        URL jwksUrl = resolveJwksUrl(config.getOidcIssuer(), config.getOidcJwksUri());
        log.info("event=oidc_validator_init issuer={} jwks_uri={}", config.getOidcIssuer(), jwksUrl);
        JWKSource<SecurityContext> keySource = JWKSourceBuilder.create(jwksUrl).build();
        return new NimbusOidcTokenValidator(keySource, config.getOidcIssuer(), config.getOidcAudience());
    }

    /**
     * Resolve the JWKS URL, either from the explicit config value or via OIDC discovery.
     * The resulting URL must use HTTPS (loopback hosts excepted) so that an on-path attacker
     * cannot swap the signing keys.
     */
    static URL resolveJwksUrl(String issuer, String jwksUri) throws Exception {
        if (jwksUri != null && !jwksUri.trim().isEmpty()) {
            return requireSecure(new URL(jwksUri.trim()));
        }
        if (issuer == null || issuer.trim().isEmpty()) {
            throw new IllegalArgumentException("Either an OIDC issuer or an explicit JWKS URI must be configured");
        }
        String base = issuer.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URL wellKnown = requireSecure(new URL(base + WELL_KNOWN_SUFFIX));
        ResourceRetriever retriever = new DefaultResourceRetriever(5000, 5000);
        Resource resource = retriever.retrieveResource(wellKnown);
        Map<String, Object> doc = JSONObjectUtils.parse(resource.getContent());
        String discovered = JSONObjectUtils.getString(doc, "jwks_uri");
        if (discovered == null || discovered.trim().isEmpty()) {
            throw new IllegalStateException("OIDC discovery document at " + wellKnown + " has no jwks_uri");
        }
        return requireSecure(new URL(discovered));
    }

    /**
     * Reject plain-HTTP key/discovery URLs unless they target a loopback host.
     */
    private static URL requireSecure(URL url) {
        if ("https".equalsIgnoreCase(url.getProtocol()) || isLoopback(url.getHost())) {
            return url;
        }
        throw new IllegalArgumentException(
                "OIDC JWKS/discovery URL must use HTTPS (got " + url + "); plain HTTP is only allowed for loopback hosts");
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase();
        return h.equals("localhost") || h.equals("127.0.0.1") || h.startsWith("127.")
                || h.equals("::1") || h.equals("[::1]");
    }

    @Override
    public String validate(String token) throws ValidationException {
        try {
            JWTClaimsSet claims = jwtProcessor.process(token, null);
            return claims.getSubject() != null ? claims.getSubject() : "oidc";
        } catch (Exception e) {
            throw new ValidationException("OIDC token validation failed: " + e.getMessage(), e);
        }
    }
}
