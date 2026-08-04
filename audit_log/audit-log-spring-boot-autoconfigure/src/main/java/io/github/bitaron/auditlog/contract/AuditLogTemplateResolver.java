package io.github.bitaron.auditlog.contract;

import io.github.bitaron.auditlog.dto.AuditLogClientData;

/**
 * A strategy interface for resolving audit log templates by replacing placeholders
 * with actual data provided through an {@link AuditLogClientData} instance.
 * <p>
 * Implementations of this interface define how a template string is processed and
 * transformed into a finalized message by integrating the dynamic content contained
 * in the {@code AuditLogClientData} object.
 * </p>
 *
 * @see AuditLogClientData
 * @since 1.0
 */
public interface AuditLogTemplateResolver {

    /**
     * Resolves the provided template by substituting placeholders with values extracted
     * from the given {@link AuditLogClientData} object.
     * <p>
     * The resolution process is implementation-specific and may involve processing tokens
     * or patterns within the template string that correspond to fields or properties of the
     * {@code AuditLogClientData} instance.
     * </p>
     *
     * @param name     the name of the template being resolved, used as an identifier for
     *                 error reporting and caching by implementations
     * @param template the template string containing placeholders to be replaced
     * @param dto      the data transfer object that provides the dynamic values for the template
     * @return a fully resolved string with all applicable placeholders replaced by actual values
     */
    String resolveTemplate(String name, String template, AuditLogClientData dto);
}
