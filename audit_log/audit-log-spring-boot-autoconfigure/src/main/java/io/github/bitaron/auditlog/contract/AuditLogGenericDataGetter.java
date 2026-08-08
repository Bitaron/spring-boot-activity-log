package io.github.bitaron.auditlog.contract;

/**
 * Resolves actor/client identity for {@code ActorSource.CONTEXT} audits. Every method is
 * {@code default}, each returning the fallback already documented on it - a consumer implementing
 * this only to customize, say, {@link #getActorId()} doesn't have to stub out the other 4 just to
 * compile. Override only what you need; the defaults match what
 * {@code DefaultAuditContextResolver} would otherwise fall back to itself.
 */
public interface AuditLogGenericDataGetter {

    /**
     * Returns the unique identifier of the actor (user/service) initiating the action.
     *
     * @return Non-null string representing the actor's system identifier.
     *         Returns "SYSTEM" for automated/background processes.
     */
    default String getActorId() {
        return "SYSTEM";
    }

    /**
     * Provides a human-readable name for the actor.
     *
     * @return Display name of the actor (e.g., "John Doe", "Batch Processor Service").
     *         Returns empty string if unavailable.
     */
    default String getActorName() {
        return "";
    }

    /**
     * Identifies the geographical location of the client.
     *
     * @return Location string in "City, Region, Country" format (e.g., "London, England, UK"),
     *         or empty string if geolocation is unavailable or disabled.
     */
    default String getClientLocation() {
        return "";
    }

    /**
     * Returns the client IP address associated with the action.
     *
     * @return IPv4/IPv6 address string. For server-initiated actions, returns "127.0.0.1".
     *         Never returns null.
     */
    default String getClientIp() {
        return "127.0.0.1";
    }

    /**
     * Provides the User-Agent string from the client's request.
     *
     * @return Full User-Agent header value, or empty string for non-HTTP clients.
     *         Example: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ..."
     */
    default String getUserAgent() {
        return "";
    }

}
