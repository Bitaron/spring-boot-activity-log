package io.github.bitaron.auditLog.repository;

import io.github.bitaron.auditLog.entity.AuditTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditTemplateRepository extends JpaRepository<AuditTemplate, Long> {
}