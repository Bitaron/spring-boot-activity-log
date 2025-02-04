package io.github.bitaron.auditLog.contract;

import io.github.bitaron.auditLog.dto.AuditLogTemplateData;

/**
 * Provides generic contextual information about the actor and environment for audit logging.
 * <p>
 * This interface supplies common audit metadata that is typically available across all audit events,
 * including actor identification and client environment details. Implementations are usually
 * context-aware, retrieving information from the execution environment (e.g., web request context,
 * security subsystem, or thread-local storage).
 *
 * <p><b>Relationship with Other Components:</b>
 * <ul>
 *   <li>Used in conjunction with {@link AuditLogDataGetter} to provide complete audit information</li>
 *   <li>Data from this interface often populates common fields in {@link AuditLogTemplateData}</li>
 * </ul>
 *
 * <p><b>Implementation Requirements:</b>
 * <ul>
 *   <li>Methods should return non-null values when information is available</li>
 *   <li>Return empty strings for unavailable non-critical information</li>
 *   <li>Implementations should be thread-safe if used in concurrent environments</li>
 * </ul>
 *
 * <p><b>Example Implementation:</b>
 * <pre>
 * public class WebRequestAuditDataGetter implements AuditLogGenericDataGetter {
 *     public String getActorId() {
 *         return SecurityContext.getCurrentUser().getId();
 *     }
 *     // ... other method implementations using request context ...
 * }
 * </pre>
 *
 * @see AuditLogDataGetter
 * @see AuditLogTemplateData
 */
public interface AuditLogGenericDataGetter {

    /**
     * Returns the unique identifier of the actor (user/service) initiating the action.
     *
     * @return Non-null string representing the actor's system identifier.
     *         Returns "SYSTEM" for automated/background processes.
     */
    String getActorId();

    /**
     * Provides a human-readable name for the actor.
     *
     * @return Display name of the actor (e.g., "John Doe", "Batch Processor Service").
     *         Returns empty string if unavailable.
     */
    String getActorName();

    /**
     * Identifies the geographical location of the client.
     *
     * @return Location string in "City, Region, Country" format (e.g., "London, England, UK"),
     *         or empty string if geolocation is unavailable or disabled.
     */
    String getClientLocation();

    /**
     * Returns the client IP address associated with the action.
     *
     * @return IPv4/IPv6 address string. For server-initiated actions, returns "127.0.0.1".
     *         Never returns null.
     */
    String getClientIp();

    /**
     * Provides the User-Agent string from the client's request.
     *
     * @return Full User-Agent header value, or empty string for non-HTTP clients.
     *         Example: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ..."
     */
    String getUserAgent();

}
