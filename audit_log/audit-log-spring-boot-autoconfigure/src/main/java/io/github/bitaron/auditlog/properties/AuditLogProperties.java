package io.github.bitaron.auditlog.properties;

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

    /**
     * Maximum number of compiled FreeMarker templates kept in memory. The cache is LRU-evicted
     * once this is exceeded, so editing a template repeatedly cannot leak compiled ASTs forever.
     */
    private int maxTemplateCacheSize = 256;

    /**
     * Delivery mode for audit writes.
     * <ul>
     *   <li>{@link DeliveryMode#ASYNC} (default) - dispatched off the caller's thread, in their
     *   own transaction. If the audited method runs inside a transaction, the write is deferred
     *   until that transaction commits, so a rolled-back business operation never leaves behind
     *   an audit record describing something that didn't happen; if it has no transaction, the
     *   write is dispatched immediately. Best-effort: a full queue or process crash can still
     *   lose a record (see {@code audit.log.records} metrics).</li>
     *   <li>{@link DeliveryMode#SYNC} - written on the caller's thread, sharing the caller's
     *   transaction (commits/rolls back atomically with it). Higher latency on the audited call,
     *   but the strongest delivery guarantee this library offers - appropriate for compliance
     *   requirements where "the operation happened but wasn't audited" is unacceptable.</li>
     * </ul>
     */
    private DeliveryMode mode = DeliveryMode.ASYNC;

    /**
     * Templates keyed by name, defined in configuration rather than the {@code audit_template}
     * table - see {@code PropertiesAuditTemplateSource}. Lets a template be versioned with the
     * application's own code/config instead of requiring a separate database write, and is tried
     * before the database so a property-defined template can override a same-named database row.
     * Example: {@code audit.log.templates.login-attempt=Login by ${actorName!"unknown"}}.
     */
    private Map<String, String> templates = new HashMap<>();

    /**
     * When {@code true}, a {@code @Audit(templates = ...)} name that no configured
     * {@code AuditTemplateSource} can resolve fails application startup instead of only logging a
     * warning at call time. Off by default since it requires eagerly scanning every bean for
     * {@code @Audit}-annotated methods, which not every application wants paid at startup.
     */
    private boolean failOnMissingTemplate = false;

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

    public enum DeliveryMode {
        ASYNC, SYNC
    }

    /** Sizing for the dedicated thread pool audit writes are submitted to. */
    @Getter
    @Setter
    public static class Executor {
        private int corePoolSize = 2;
        private int maxPoolSize = 10;
        private int queueCapacity = 500;

        /**
         * How long to wait for queued/running audit writes to finish during a graceful
         * application shutdown before giving up on the remainder. Writes still queued after this
         * timeout are reported via the {@code audit.log.records{outcome=dropped_on_shutdown}}
         * counter rather than being silently discarded.
         */
        private int awaitTerminationSeconds = 30;
    }
}
