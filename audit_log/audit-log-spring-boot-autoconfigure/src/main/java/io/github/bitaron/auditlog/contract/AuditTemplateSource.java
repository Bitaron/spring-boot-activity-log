package io.github.bitaron.auditlog.contract;

import java.util.Optional;

/**
 * Strategy for resolving a named template's content, independent of where it comes from.
 * <p>
 * {@link io.github.bitaron.auditlog.core.AuditLogWriter} consults every {@code AuditTemplateSource}
 * bean, in {@link org.springframework.core.annotation.Order} order, and uses the first one that
 * resolves a given name - so multiple sources can coexist, each covering different templates (or
 * intentionally overriding one another).
 * <p>
 * <b>Tenant-scoped (WP16):</b> {@code tenantId} is the tenant the current audit event belongs to
 * (from {@link AuditTenantResolver}), or {@code null} when multi-tenancy is disabled/unresolved. A
 * source that supports per-tenant templates should prefer a tenant-specific match over a global
 * one, falling back to the global template when no tenant-specific override exists - see
 * {@link io.github.bitaron.auditlog.core.PropertiesAuditTemplateSource} and
 * {@link io.github.bitaron.auditlog.core.DatabaseAuditTemplateSource} for the reference
 * implementations of that fallback. A source with no notion of tenancy (e.g. one reading from a
 * single classpath resource bundle) may simply ignore {@code tenantId} and always resolve
 * globally.
 *
 * @see io.github.bitaron.auditlog.core.PropertiesAuditTemplateSource
 * @see io.github.bitaron.auditlog.core.DatabaseAuditTemplateSource
 */
public interface AuditTemplateSource {

    /**
     * Resolves the raw template text for the given name.
     *
     * @param tenantId the current tenant, or {@code null} if multi-tenancy is disabled/unresolved
     * @param name     the template name, as it appears in {@code @Audit(templates = ...)}
     * @return the template's content, or empty if this source has no template by that name
     */
    Optional<String> findTemplate(String tenantId, String name);
}
