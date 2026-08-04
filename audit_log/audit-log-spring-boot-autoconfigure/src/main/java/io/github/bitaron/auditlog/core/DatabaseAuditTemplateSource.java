package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.contract.AuditTemplateSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import org.springframework.core.annotation.Order;

import java.util.Optional;

/**
 * Resolves templates stored in the {@code audit_template} table.
 * <p>
 * Query lives here rather than in {@link AuditLogWriter} - the writer must not build queries of
 * its own beyond persisting what it's given; sourcing where a template's content comes from is a
 * separate concern from persisting the audit record.
 */
@Order(1)
public class DatabaseAuditTemplateSource implements AuditTemplateSource {

    private final EntityManager entityManager;

    public DatabaseAuditTemplateSource(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<String> findTemplate(String name) {
        try {
            TypedQuery<String> query = entityManager.createQuery(
                    "select t.template from AuditTemplate t where t.name = :name", String.class);
            query.setParameter("name", name);
            return Optional.ofNullable(query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
}
