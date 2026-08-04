package io.github.bitaron.auditlog.entity;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * One persisted audit record, produced by an {@code @Audit}-annotated method invocation matching
 * a single template (or, when the invocation requested no templates, a single default record).
 * <p>
 * The {@link #data} column requires a JSON-capable Hibernate dialect for
 * {@code @JdbcTypeCode(SqlTypes.JSON)} to map correctly (PostgreSQL, MySQL 5.7+, and H2 in
 * PostgreSQL-compatibility mode all qualify). On a dialect without native JSON support, switch
 * this to a plain {@code @Lob} text column.
 */
@Getter
@Setter
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_log_created_at", columnList = "created_at"),
        @Index(name = "idx_audit_log_actor_id", columnList = "actor_id"),
        @Index(name = "idx_audit_log_audit_type", columnList = "audit_type")
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

    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "message", length = 4000)
    private String message;

    @Column(name = "group_id")
    private Long groupId;

}
