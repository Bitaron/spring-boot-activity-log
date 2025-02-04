package io.github.bitaron.auditLog.repository;

import io.github.bitaron.auditLog.entity.AuditTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditTemplateRepository extends JpaRepository<AuditTemplate, Long> {
    List<AuditTemplate> findAllByName(List<String> templateList);
}