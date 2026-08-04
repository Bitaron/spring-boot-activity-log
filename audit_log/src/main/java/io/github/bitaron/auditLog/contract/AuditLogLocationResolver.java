package io.github.bitaron.auditLog.contract;

/**
 * Optional strategy for resolving a human-readable location from a client IP address.
 * <p>
 * The starter ships no default implementation - IP geolocation typically requires a paid or
 * self-hosted database (e.g. MaxMind GeoIP2) that this library should not bundle. Register a
 * bean of this type to populate {@code AuditLog.clientLocation}; without one, the field is left
 * {@code null}.
 *
 * @since 1.1
 */
public interface AuditLogLocationResolver {

    /**
     * @param clientIp the resolved client IP address; never {@code null}
     * @return a human-readable location (e.g. "London, England, UK"), or {@code null} if unknown
     */
    String resolveLocation(String clientIp);
}
