package io.github.bitaron.auditLog.entity;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Basic(optional = false)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id")
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

    @Column(name = "message")
    private String message;

    @Column(name = "group_id")
    private String groupId;

}
