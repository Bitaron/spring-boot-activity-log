package io.github.bitaron.auditLog.repository;

import io.github.bitaron.auditLog.entity.AuditGroup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditGroupRepository extends JpaRepository<AuditGroup, Long> {
}