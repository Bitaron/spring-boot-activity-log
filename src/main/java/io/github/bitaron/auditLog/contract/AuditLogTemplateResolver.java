package io.github.bitaron.auditLog.contract;

import io.github.bitaron.auditLog.dto.AuditLogClientData;
import io.github.bitaron.auditLog.dto.AuditLogTemplateData;

/**
 * A strategy interface for resolving audit log templates by replacing placeholders
 * with actual data provided through an {@link AuditLogTemplateData} instance.
 * <p>
 * Implementations of this interface define how a template string is processed and
 * transformed into a finalized message by integrating the dynamic content contained
 * in the {@code AuditLogTemplateData} object.
 * </p>
 *
 * @see AuditLogTemplateData
 * @since 1.0
 */
public interface AuditLogTemplateResolver {

    /**
     * Resolves the provided template by substituting placeholders with values extracted
     * from the given {@link AuditLogTemplateData} object.
     * <p>
     * The resolution process is implementation-specific and may involve processing tokens
     * or patterns within the template string that correspond to fields or properties of the
     * {@code AuditLogTemplateData} instance.
     * </p>
     *
     * @param template the template string containing placeholders to be replaced
     * @param dto      the data transfer object that provides the dynamic values for the template
     * @return a fully resolved string with all applicable placeholders replaced by actual values
     */
    String resolveTemplate(String name, String template, AuditLogClientData dto);
}
