package io.github.bitaron.auditLog.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Configuration properties for the audit-log starter, bound under the {@code audit.log} prefix.
 * <p>
 * Registered via {@code @EnableConfigurationProperties} on the starter's auto-configuration
 * rather than being a {@code @Component} itself: a library class in a package the consuming
 * application never component-scans would otherwise never become a bean, and the
 * {@code @Autowired AuditLogProperties} that depended on it would fail context startup.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "audit.log")
public class AuditLogProperties {

    public static final String REQUESTER_ID = "requesterId";
    public static final String REQUESTER_NAME = "requesterName";

    /**
     * Master switch for the starter. Disabling it skips registering the aspect, the executor,
     * and the JPA entity scan entirely.
     */
    private boolean enabled = true;

    /**
     * Map of audit actor identifiers to their corresponding HTTP header names.
     * Example: requesterId -> X-User-Id
     */
    private Map<String, String> headerMappings = new HashMap<>();

    /**
     * Whether to trust client-supplied proxy headers (X-Forwarded-For, Proxy-Client-IP,
     * WL-Proxy-Client-IP) when resolving the client IP. Off by default: these headers are
     * trivially spoofable by the caller unless a trusted reverse proxy strips/overwrites them
     * before the request reaches this application, which the starter has no way to verify.
     */
    private boolean trustForwardedHeaders = false;

    /**
     * Top-level field names to redact (replaced with "***") when method arguments are
     * serialized into the audit record's {@code data} column. Case-sensitive; matches by
     * field/property name at any depth in the serialized argument graph.
     */
    private Set<String> maskedFields = new HashSet<>(Set.of(
            "password", "secret", "token", "authorization", "creditCardNumber"));

    /**
     * Maximum size, in characters, of the serialized argument/response JSON persisted per audit
     * record. Larger payloads are truncated to protect the audit_log table from runaway rows.
     */
    private int maxSerializedDataLength = 8192;

    private final Executor executor = new Executor();

    public String getHeaderFor(String type) {
        String value = headerMappings.getOrDefault(type, "");
        if (value.isEmpty()) {
            if (type.equals(REQUESTER_NAME)) {
                return "X-USER-NAME";
            }
            if (type.equals(REQUESTER_ID)) {
                return "X-USER-ID";
            }
        }
        return value;
    }

    /** Sizing for the dedicated thread pool audit writes are submitted to. */
    @Getter
    @Setter
    public static class Executor {
        private int corePoolSize = 2;
        private int maxPoolSize = 10;
        private int queueCapacity = 500;
    }
}
