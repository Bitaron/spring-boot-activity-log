package io.github.bitaron.auditlog.contract;

import java.util.Optional;

/**
 * Strategy for resolving a named template's content, independent of where it comes from.
 * <p>
 * {@link io.github.bitaron.auditlog.core.AuditLogWriter} consults every {@code AuditTemplateSource}
 * bean, in {@link org.springframework.core.annotation.Order} order, and uses the first one that
 * resolves a given name - so multiple sources can coexist, each covering different templates (or
 * intentionally overriding one another).
 *
 * @see io.github.bitaron.auditlog.core.PropertiesAuditTemplateSource
 * @see io.github.bitaron.auditlog.core.DatabaseAuditTemplateSource
 */
public interface AuditTemplateSource {

    /**
     * Resolves the raw template text for the given name.
     *
     * @param name the template name, as it appears in {@code @Audit(templates = ...)}
     * @return the template's content, or empty if this source has no template by that name
     */
    Optional<String> findTemplate(String name);
}
