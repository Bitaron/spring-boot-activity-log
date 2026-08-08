package io.github.bitaron.auditlog.entity;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One persisted audit record, produced by exactly one {@code @Audit}-annotated method invocation
 * - regardless of how many templates it named. Each rendered template is a child
 * {@link AuditLogMessage} row rather than a duplicate {@code AuditLog} row, so actor/client/
 * outcome fields are recorded exactly once per invocation.
 * <p>
 * Rows are never updated after insert (see {@link Immutable}) and {@link #data} is intentionally
 * limited to {@code {args, result, exception, exceptionThrown}} - actor/client identity already
 * has dedicated columns and must not be duplicated into the JSON payload.
 * <p>
 * The {@link #data} column requires a JSON-capable Hibernate dialect for
 * {@code @JdbcTypeCode(SqlTypes.JSON)} to map correctly (PostgreSQL, MySQL 5.7+, and H2 in
 * PostgreSQL-compatibility mode all qualify). On a dialect without native JSON support, switch
 * this to a plain {@code @Lob} text column.
 */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_log_created_at", columnList = "created_at"),
        @Index(name = "idx_audit_log_actor_id", columnList = "actor_id"),
        @Index(name = "idx_audit_log_audit_type", columnList = "audit_type"),
        // Tenant-first composite: once audit.log.multi-tenancy.enabled=true, the hot-path read is
        // "this tenant's records, newest first" (see JpaAuditLogQueryService's mandatory tenant
        // predicate) - a single-column tenant_id index alone would still force a secondary sort.
        @Index(name = "idx_audit_log_tenant_created_at", columnList = "tenant_id, created_at")
})
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Basic(optional = false)
    @Column(name = "id")
    private Long id;

    @Column(name = "audit_type")
    private String auditType;

    @Column(name = "actor_id")
    private String actorId;

    @Column(name = "actor_name")
    private String actorName;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "client_location")
    private String clientLocation;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "action_type")
    private String actionType;

    @Column(name = "action_name")
    private String actionName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data")
    private String data;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16)
    private AuditOutcome outcome;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "group_id")
    private Long groupId;

    /** Null when multi-tenancy is disabled or unresolvable - see {@code AuditTenantResolver}. */
    @Column(name = "tenant_id")
    private String tenantId;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuditLog that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
