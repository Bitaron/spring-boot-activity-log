package io.github.bitaron.auditlog.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.util.Objects;

/**
 * One rendered template message belonging to an {@link AuditLog} event. A single audited method
 * invocation naming N templates in {@code @Audit(templates = ...)} produces one {@link AuditLog}
 * row and N of these child rows - never N separate {@code AuditLog} rows for one event.
 * <p>
 * Identified by {@link #templateName} rather than a database foreign key: templates can come
 * from any configured {@code AuditTemplateSource} (properties, the {@code audit_template} table,
 * or a consumer-supplied one), and not every source has a database row to key on.
 */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "audit_log_message")
public class AuditLogMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Basic(optional = false)
    @Column(name = "id")
    private Long id;

    @Column(name = "audit_log_id", nullable = false)
    private Long auditLogId;

    @Column(name = "template_name")
    private String templateName;

    @Column(name = "message", length = 4000)
    private String message;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuditLogMessage that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
