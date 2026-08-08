package io.github.bitaron.auditlog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/**
 * Tenant-scoped (WP16): {@link #tenantId} uses {@code ""} (never {@code null}) as the sentinel for
 * "global, not tenant-specific" - deliberately different from {@code AuditLog.tenantId}'s
 * "{@code null} = default tenant" convention, because this column participates in a real composite
 * unique constraint ({@code (tenant_id, name)}) and standard SQL treats every {@code NULL} as
 * distinct for uniqueness purposes - a {@code NULL}-based convention here would silently allow
 * duplicate global template names. See {@link #GLOBAL_TENANT_ID}.
 */
@Getter
@Setter
@Entity
@Table(name = "audit_template", uniqueConstraints = {
        @UniqueConstraint(name = "uk_audit_template_tenant_name", columnNames = {"tenant_id", "name"})
})
public class AuditTemplate {

    /** Sentinel {@link #tenantId} for "not tenant-specific" - see the class javadoc. */
    public static final String GLOBAL_TENANT_ID = "";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Basic(optional = false)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "template", length = 4000)
    private String template;

    // columnDefinition (not just nullable=false) so ddl-auto-generated schemas get a real SQL-level
    // DEFAULT too, matching the manual db/migration/V4 migration - otherwise a raw INSERT that
    // omits this column (any seed data script, any hand-written SQL) would hit a NOT NULL
    // violation instead of picking up GLOBAL_TENANT_ID the way relying on the Java field default
    // alone only would for JPA-persisted rows.
    @Column(name = "tenant_id", nullable = false, columnDefinition = "varchar(255) default ''")
    private String tenantId = GLOBAL_TENANT_ID;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuditTemplate that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
