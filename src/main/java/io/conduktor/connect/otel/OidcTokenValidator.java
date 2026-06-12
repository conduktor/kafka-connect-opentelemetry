package io.conduktor.connect.otel;

/**
 * Validates OIDC bearer tokens. Implementations verify the token signature against the
 * issuer's published keys and check standard claims (exp, iss, aud).
 */
public interface OidcTokenValidator {

    /**
     * Validate a bearer token.
     *
     * @param token the raw JWT (without the "Bearer " prefix)
     * @return the authenticated principal (the token subject)
     * @throws ValidationException if the token is invalid, expired, or fails any claim check
     */
    String validate(String token) throws ValidationException;

    /** Thrown when a token fails validation. */
    class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }

        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
