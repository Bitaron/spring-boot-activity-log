package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.contract.AuditTemplateSource;
import io.github.bitaron.auditlog.entity.AuditTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import org.springframework.core.annotation.Order;

import java.util.Optional;

/**
 * Resolves templates stored in the {@code audit_template} table.
 * <p>
 * Tenant-scoped (WP16): a row tagged with the current tenant is preferred over a global
 * ({@link AuditTemplate#GLOBAL_TENANT_ID}) row of the same name, so a tenant can override a
 * shared/default template without duplicating every other tenant's copy of it. A deployment with
 * multi-tenancy disabled (or a name no tenant has overridden) always resolves the global row.
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
    public Optional<String> findTemplate(String tenantId, String name) {
        if (tenantId != null && !tenantId.equals(AuditTemplate.GLOBAL_TENANT_ID)) {
            Optional<String> tenantSpecific = findByTenantAndName(tenantId, name);
            if (tenantSpecific.isPresent()) {
                return tenantSpecific;
            }
        }
        return findByTenantAndName(AuditTemplate.GLOBAL_TENANT_ID, name);
    }

    private Optional<String> findByTenantAndName(String tenantId, String name) {
        try {
            TypedQuery<String> query = entityManager.createQuery(
                    "select t.template from AuditTemplate t where t.name = :name and t.tenantId = :tenantId",
                    String.class);
            query.setParameter("name", name);
            query.setParameter("tenantId", tenantId);
            return Optional.ofNullable(query.getSingleResult());
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
}
