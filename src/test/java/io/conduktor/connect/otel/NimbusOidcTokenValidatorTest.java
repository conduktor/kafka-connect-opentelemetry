package io.conduktor.connect.otel;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OIDC token validation against an in-memory JWKS (no network).
 */
class NimbusOidcTokenValidatorTest {

    private static final String ISSUER = "https://idp.example/realms/otlp";
    private static final String AUDIENCE = "otlp-connector";

    private RSAKey signingKey;       // the key the IdP signs with (private + public)
    private JWKSource<SecurityContext> keySource; // public JWKS the validator trusts

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        keySource = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
    }

    private NimbusOidcTokenValidator validator(String audience) {
        return new NimbusOidcTokenValidator(keySource, ISSUER, audience);
    }

    private String token(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .subject("service-account-app")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000));
    }

    @Test
    void acceptsValidTokenAndReturnsSubject() throws Exception {
        String jwt = token(signingKey, validClaims().build());

        String principal = validator(AUDIENCE).validate(jwt);

        assertEquals("service-account-app", principal);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String jwt = token(signingKey, validClaims()
                .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                .build());

        assertThrows(OidcTokenValidator.ValidationException.class,
                () -> validator(AUDIENCE).validate(jwt));
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        String jwt = token(signingKey, validClaims()
                .issuer("https://evil.example")
                .build());

        assertThrows(OidcTokenValidator.ValidationException.class,
                () -> validator(AUDIENCE).validate(jwt));
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        String jwt = token(signingKey, validClaims()
                .audience("some-other-service")
                .build());

        assertThrows(OidcTokenValidator.ValidationException.class,
                () -> validator(AUDIENCE).validate(jwt));
    }

    @Test
    void rejectsTokenSignedByUnknownKey() throws Exception {
        RSAKey rogueKey = new RSAKeyGenerator(2048).keyID("rogue").generate();
        String jwt = token(rogueKey, validClaims().build());

        assertThrows(OidcTokenValidator.ValidationException.class,
                () -> validator(AUDIENCE).validate(jwt));
    }

    @Test
    void rejectsGarbageToken() {
        assertThrows(OidcTokenValidator.ValidationException.class,
                () -> validator(AUDIENCE).validate("not-a-jwt"));
    }

    @Test
    void skipsAudienceCheckWhenAudienceNotConfigured() throws Exception {
        // Audience not configured -> token with any audience is accepted (signature + iss + exp still checked)
        String jwt = token(signingKey, validClaims()
                .audience("anything-goes")
                .build());

        String principal = validator("").validate(jwt);

        assertEquals("service-account-app", principal);
    }
}
