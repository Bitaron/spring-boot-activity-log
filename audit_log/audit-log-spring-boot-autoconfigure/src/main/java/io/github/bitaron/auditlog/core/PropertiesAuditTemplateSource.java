package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.contract.AuditTemplateSource;
import io.github.bitaron.auditlog.properties.AuditLogProperties;
import org.springframework.core.annotation.Order;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves templates defined in configuration rather than the {@code audit_template} database
 * table - see {@link AuditLogProperties}. Two layers, tenant-specific tried first:
 * <ol>
 *   <li>{@code audit.log.tenant-templates.<tenantId>.<name>=<template>} - only consulted when
 *   {@code tenantId} is non-null and has an entry under it.</li>
 *   <li>{@code audit.log.templates.<name>=<template>} - the tenant-agnostic default, also what a
 *   single-tenant deployment (multi-tenancy disabled) always resolves against.</li>
 * </ol>
 * Ordered ahead of {@link DatabaseAuditTemplateSource} so a template versioned alongside
 * application code can deliberately override a same-named database row.
 */
@Order(0)
public class PropertiesAuditTemplateSource implements AuditTemplateSource {

    private final AuditLogProperties auditLogProperties;

    public PropertiesAuditTemplateSource(AuditLogProperties auditLogProperties) {
        this.auditLogProperties = auditLogProperties;
    }

    @Override
    public Optional<String> findTemplate(String tenantId, String name) {
        if (tenantId != null) {
            Map<String, String> tenantTemplates = auditLogProperties.getTenantTemplates().get(tenantId);
            if (tenantTemplates != null && tenantTemplates.containsKey(name)) {
                return Optional.of(tenantTemplates.get(name));
            }
        }
        return Optional.ofNullable(auditLogProperties.getTemplates().get(name));
    }
}
