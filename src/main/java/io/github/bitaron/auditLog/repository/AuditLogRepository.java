package io.github.bitaron.auditLog.repository;

import io.github.bitaron.auditLog.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}