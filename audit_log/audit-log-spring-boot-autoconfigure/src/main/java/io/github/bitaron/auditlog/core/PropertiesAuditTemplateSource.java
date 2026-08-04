package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.contract.AuditTemplateSource;
import io.github.bitaron.auditlog.properties.AuditLogProperties;
import org.springframework.core.annotation.Order;

import java.util.Optional;

/**
 * Resolves templates defined in configuration ({@code audit.log.templates.<name>=<template>})
 * rather than the {@code audit_template} database table - see {@link AuditLogProperties}.
 * <p>
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
    public Optional<String> findTemplate(String name) {
        return Optional.ofNullable(auditLogProperties.getTemplates().get(name));
    }
}
