package io.github.bitaron.auditlog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/**
 * Tenant-scoped (WP16) the same way as {@link AuditTemplate} - see that class's javadoc for why
 * {@link #tenantId} uses {@code ""}, never {@code null}, as its "not tenant-specific" sentinel.
 */
@Getter
@Setter
@Entity
@Table(name = "audit_group", uniqueConstraints = {
        @UniqueConstraint(name = "uk_audit_group_tenant_name", columnNames = {"tenant_id", "name"})
})
public class AuditGroup {

    /** Sentinel {@link #tenantId} for "not tenant-specific" - see the class javadoc. */
    public static final String GLOBAL_TENANT_ID = "";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Basic(optional = false)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    // See AuditTemplate.tenantId's field comment for why columnDefinition, not just
    // nullable=false, is used here too.
    @Column(name = "tenant_id", nullable = false, columnDefinition = "varchar(255) default ''")
    private String tenantId = GLOBAL_TENANT_ID;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuditGroup that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
