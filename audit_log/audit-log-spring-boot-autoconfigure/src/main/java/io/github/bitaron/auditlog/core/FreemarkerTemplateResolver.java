package io.github.bitaron.auditlog.core;

import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import io.github.bitaron.auditlog.contract.AuditLogTemplateResolver;
import io.github.bitaron.auditlog.model.AuditContext;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Default {@link AuditLogTemplateResolver}, backed by FreeMarker.
 * <p>
 * <b>Trust boundary:</b> templates are stored in the {@code audit_template} database table and
 * executed as FreeMarker templates against the audited method's arguments/response. This
 * configuration disables the {@code ?api} built-in and uses
 * {@link TemplateClassResolver#SAFER_RESOLVER} to block reflective escapes into arbitrary
 * classes, but write access to {@code audit_template} is still effectively the ability to
 * execute template logic in this process - restrict who can write to that table the same way
 * you would restrict deploy access.
 */
public class FreemarkerTemplateResolver implements AuditLogTemplateResolver {

    private static final Configuration FREEMARKER_CONFIG;

    static {
        FREEMARKER_CONFIG = new Configuration(Configuration.VERSION_2_3_34);
        FREEMARKER_CONFIG.setDefaultEncoding("UTF-8");
        FREEMARKER_CONFIG.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        FREEMARKER_CONFIG.setNewBuiltinClassResolver(TemplateClassResolver.SAFER_RESOLVER);
        FREEMARKER_CONFIG.setAPIBuiltinEnabled(false);  // Disable ?api
        FREEMARKER_CONFIG.setLogTemplateExceptions(true);
    }

    // Parsing a FreeMarker template compiles it into an AST; re-parsing identical template text
    // on every single @Audit invocation is wasted work under load, so compiled templates are
    // cached by name + content hash - the hash guards against a template being edited in the
    // audit_template table without a name change. Bounded (LRU-evicted) rather than a plain
    // ConcurrentHashMap: an unbounded cache leaks one compiled AST per edit ever made to a
    // template's content, for the lifetime of the JVM.
    private final Map<String, Template> compiledTemplates;

    public FreemarkerTemplateResolver(int maxCacheSize) {
        this.compiledTemplates = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Template> eldest) {
                return size() > maxCacheSize;
            }
        });
    }

    @Override
    public String resolveTemplate(String name, String template, AuditContext context) {
        try {
            Template freemarkerTemplate = compiledTemplates.computeIfAbsent(
                    cacheKey(name, template), key -> compile(name, template));
            StringWriter writer = new StringWriter();
            freemarkerTemplate.process(context, writer);
            return writer.toString();
        } catch (TemplateException | IOException e) {
            throw new RuntimeException("Failed to resolve audit log template \"" + name + "\"", e);
        }
    }

    private Template compile(String name, String template) {
        try (StringReader reader = new StringReader(template)) {
            return new Template(name, reader, FREEMARKER_CONFIG);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse audit log template \"" + name + "\"", e);
        }
    }

    private String cacheKey(String name, String template) {
        return name + "#" + Objects.hashCode(template);
    }

    /** Visible for testing the bounded-cache behavior. */
    int cacheSize() {
        return compiledTemplates.size();
    }
}
