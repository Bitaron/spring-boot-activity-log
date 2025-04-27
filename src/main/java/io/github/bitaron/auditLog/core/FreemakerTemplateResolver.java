package io.github.bitaron.auditLog.core;

import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import io.github.bitaron.auditLog.contract.AuditLogTemplateResolver;
import io.github.bitaron.auditLog.dto.AuditLogClientData;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

public class FreemakerTemplateResolver implements AuditLogTemplateResolver {

    // FreeMarker configuration (thread-safe)
    private static final Configuration FREEMARKER_CONFIG;

    static {
        FREEMARKER_CONFIG = new Configuration(Configuration.VERSION_2_3_34);
        FREEMARKER_CONFIG.setDefaultEncoding("UTF-8");
        FREEMARKER_CONFIG.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        FREEMARKER_CONFIG.setLogTemplateExceptions(false);
        FREEMARKER_CONFIG.setNewBuiltinClassResolver(TemplateClassResolver.SAFER_RESOLVER);
        FREEMARKER_CONFIG.setAPIBuiltinEnabled(false);  // Disable ?api
        FREEMARKER_CONFIG.setLogTemplateExceptions(true); // Log errors
    }

    @Override
    public String resolveTemplate(String name, String template, AuditLogClientData dto) {
        try (StringReader reader = new StringReader(template)) {
            Template freemarkerTemplate = new Template(name, reader,
                    FREEMARKER_CONFIG);
            StringWriter writer = new StringWriter();

            // Process the template with the DTO as the data model
            freemarkerTemplate.process(dto, writer);
            return writer.toString();

        } catch (IOException | TemplateException e) {
            throw new RuntimeException("Failed to resolve audit log template", e);
        }
    }
}

