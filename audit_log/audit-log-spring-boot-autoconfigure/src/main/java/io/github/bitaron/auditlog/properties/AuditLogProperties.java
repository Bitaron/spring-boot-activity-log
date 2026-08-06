package io.github.bitaron.auditlog.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
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
 * <p>
 * {@code @Validated}'s JSR-303 constraints are only actually enforced when a validator
 * implementation (e.g. {@code spring-boot-starter-validation}) is on the consuming application's
 * classpath; otherwise Spring Boot binds properties without validating them, same as if the
 * annotations weren't there.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "audit.log")
public class AuditLogProperties {

    /**
     * Master switch for the starter. Disabling it skips registering the aspect, the executor,
     * and the JPA entity scan entirely.
     */
    private boolean enabled = true;

    /** HTTP header names the default actor/client resolution reads from. */
    @Valid
    private final Headers headers = new Headers();

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
    @Min(1)
    private int maxSerializedDataLength = 8192;

    /**
     * Maximum number of compiled FreeMarker templates kept in memory. The cache is LRU-evicted
     * once this is exceeded, so editing a template repeatedly cannot leak compiled ASTs forever.
     */
    @Min(1)
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
    @NotNull
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

    @Valid
    private final Executor executor = new Executor();

    /**
     * Whether to fail application startup if any of this starter's required tables are missing
     * from the configured database - see {@code AuditSchemaValidator}. On by default; turn off
     * for a deployment that already validates its schema some other way.
     */
    @Valid
    private final SchemaValidation schemaValidation = new SchemaValidation();

    /** Bounds and sort rules for {@code AuditLogQueryService} reads - see {@link Query}. */
    @Valid
    private final Query query = new Query();

    /** Scheduled deletion of old audit records - off by default; see {@link Retention}. */
    @Valid
    private final Retention retention = new Retention();

    /** Opt-in tenant tagging/scoping - off by default; see {@link MultiTenancy}. */
    @Valid
    private final MultiTenancy multiTenancy = new MultiTenancy();

    public enum DeliveryMode {
        ASYNC, SYNC
    }

    /**
     * Typed replacement for the pre-2.0 {@code Map<String,String> headerMappings} +
     * {@code getHeaderFor(String)} lookup - the header this starter reads for a given purpose is
     * a fixed, known set of two, not an open-ended map keyed by string.
     */
    @Getter
    @Setter
    public static class Headers {
        @NotNull
        private String requesterId = "X-USER-ID";
        @NotNull
        private String requesterName = "X-USER-NAME";

        /** Header {@link io.github.bitaron.auditlog.core.DefaultAuditTenantResolver} reads from. */
        @NotNull
        private String tenantId = "X-TENANT-ID";
    }

    /** See {@link #schemaValidation}. */
    @Getter
    @Setter
    public static class SchemaValidation {
        private boolean enabled = true;
    }

    /** Sizing for the dedicated thread pool audit writes are submitted to. */
    @Getter
    @Setter
    public static class Executor {
        @Min(1)
        private int corePoolSize = 2;
        @Min(1)
        private int maxPoolSize = 10;
        @Min(1)
        private int queueCapacity = 500;

        /**
         * How long to wait for queued/running audit writes to finish during a graceful
         * application shutdown before giving up on the remainder. Writes still queued after this
         * timeout are reported via the {@code audit.log.records{outcome=dropped_on_shutdown}}
         * counter rather than being silently discarded.
         */
        @Min(0)
        private int awaitTerminationSeconds = 30;
    }

    /** See {@link #query}. */
    @Getter
    @Setter
    public static class Query {
        /**
         * Largest page size {@code AuditLogQueryService.find} accepts; a larger
         * {@code Pageable} is rejected with {@code IllegalArgumentException} rather than silently
         * clamped, matching this starter's "fail loud, don't guess" convention elsewhere (see the
         * A1-A3 fixes in {@code MIGRATION.md}). Guards against one caller-supplied page size
         * turning into an unbounded table scan as {@code audit_log} grows; see
         * {@code AuditLogQueryService#findAfter} for keyset pagination once offset pagination
         * itself becomes the bottleneck, independent of page size.
         */
        @Min(1)
        private int maxPageSize = 200;
    }

    /** See {@link #retention}. */
    @Getter
    @Setter
    public static class Retention {
        /**
         * Master switch for the scheduled retention/purge job - see
         * {@code AuditLogRetentionService}. Off by default: deleting audit history is a decision
         * this starter must never make for a consuming application unasked.
         */
        private boolean enabled = false;

        /**
         * Audit records older than this are eligible for deletion. Required (no default) when
         * {@link #enabled} is {@code true} - there is no safe default retention window to assume
         * for a compliance artifact.
         */
        private Duration maxAge;

        /** Cron expression (Spring's six-field form, seconds first) the purge job runs on. */
        @NotNull
        private String cron = "0 0 3 * * *";

        /**
         * Rows deleted per batch iteration. Purging happens as a loop of bounded batches, oldest
         * row first, rather than one unbounded {@code DELETE} that could hold a long lock on
         * {@code audit_log} while a large backlog is cleared.
         */
        @Min(1)
        private int batchSize = 1000;
    }

    /** See {@link #multiTenancy}. */
    @Getter
    @Setter
    public static class MultiTenancy {
        /**
         * Master switch for tenant tagging/scoping - see
         * {@code io.github.bitaron.auditlog.contract.AuditTenantResolver}. Off by default: with
         * no flag flip, an upgrade to this version changes nothing - {@code tenant_id} stays
         * {@code null} on every row and reads are never tenant-filtered, exactly like today.
         * When {@code true}, a {@code AuditTenantResolver} bean is registered (the header-based
         * {@code DefaultAuditTenantResolver} unless overridden) and every read through
         * {@code AuditLogQueryService} is scoped to whatever tenant it resolves, failing closed
         * (throwing) if it resolves to none - see {@code JpaAuditLogQueryService}.
         */
        private boolean enabled = false;
    }
}
