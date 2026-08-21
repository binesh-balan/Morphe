package stirling.software.common.constants;

/**
 * Centralized constants for JWT token management.
 *
 * <p>These defaults are used when configuration values are not explicitly set.
 */
public final class JwtConstants {

    private JwtConstants() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Default JWT access token lifetime in minutes (24 hours). */
    public static final int DEFAULT_TOKEN_EXPIRY_MINUTES = 1440;

    /** Default desktop client token lifetime in minutes (30 days). */
    public static final int DEFAULT_DESKTOP_TOKEN_EXPIRY_MINUTES = 43200;

    /**
     * Default refresh grace period in minutes.
     *
     * <p>Allows refresh of expired tokens within this window after expiration.
     */
    public static final int DEFAULT_REFRESH_GRACE_MINUTES = 15;

    /**
     * Default allowed clock skew in seconds.
     *
     * <p>Tolerates small time drift between client and server clocks during validation.
     */
    public static final int DEFAULT_CLOCK_SKEW_SECONDS = 60;

    /** Milliseconds per minute. */
    public static final long MILLIS_PER_MINUTE = 60_000L;

    /** Seconds per minute. */
    public static final long SECONDS_PER_MINUTE = 60L;

    /**
     * Name of the HttpOnly cookie carrying the session JWT.
     *
     * <p>Morphe-PDF: the token was previously held in browser localStorage, where any script in the
     * page origin could read it. It is now delivered as an HttpOnly cookie so script cannot reach
     * it. Bearer-token authentication remains supported for the desktop client and programmatic API
     * callers.
     */
    public static final String JWT_COOKIE_NAME = "stirling_jwt";

    /** JWT issuer identifier. */
    public static final String ISSUER = "https://stirling.com";

    /**
     * Maximum refresh attempts allowed within the grace period window.
     *
     * <p>Prevents abuse of expired tokens by limiting refresh attempts.
     */
    public static final int MAX_REFRESH_ATTEMPTS_IN_GRACE = 3;
}
