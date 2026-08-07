package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.contract.AuditTemplateSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Enabled only when {@code audit.log.fail-on-missing-template=true}: scans every bean for
 * {@code @Audit}-annotated methods once all singletons are up, and fails application startup if
 * any named template can't be resolved by any configured {@link AuditTemplateSource} - instead of
 * the default behavior of {@link AuditLogWriter} logging a warning and skipping it per call.
 * <p>
 * Off by default because it means eagerly resolving a bean's real class (unwrapping any AOP
 * proxy) and reflectively scanning its methods for every singleton in the context, which not
 * every application wants to pay for at startup.
 * <p>
 * <b>Tenant-scoped templates (WP16) are only partially covered by this check</b>: startup
 * validation runs statically, with no per-tenant {@link io.github.bitaron.auditlog.model.AuditContext}
 * to resolve a tenant from and no way to enumerate every tenant that will ever call an audited
 * method - so it only resolves each template against the tenant-agnostic/global layer (a
 * {@code null} tenant). A template that's only defined per-tenant (via
 * {@code audit.log.tenant-templates.<tenantId>.<name>} or a tenant-tagged {@code audit_template}
 * row, with no global fallback) will be reported missing here even though it resolves correctly at
 * call time for the tenant it's actually defined for - a known, deliberate false positive rather
 * than silently skipping tenant-specific templates from validation entirely.
 */
@Slf4j
public class AuditTemplateValidator implements SmartInitializingSingleton {

    private final ConfigurableListableBeanFactory beanFactory;
    private final List<AuditTemplateSource> auditTemplateSources;

    public AuditTemplateValidator(ConfigurableListableBeanFactory beanFactory,
                                   List<AuditTemplateSource> auditTemplateSources) {
        this.beanFactory = beanFactory;
        this.auditTemplateSources = auditTemplateSources;
    }

    @Override
    public void afterSingletonsInstantiated() {
        TreeSet<String> missing = new TreeSet<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = beanFactory.getType(beanName);
            if (type == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(type);
            // getDeclaredMethods() (via doWithMethods), not getMethods(): audited methods are
            // routinely package-private or protected, not just public.
            ReflectionUtils.doWithMethods(userClass, method -> {
                for (Audit audit : method.getAnnotationsByType(Audit.class)) {
                    for (String templateName : audit.templates()) {
                        if (findTemplate(templateName).isEmpty()) {
                            missing.add(templateName + " (" + userClass.getSimpleName() + "#" + method.getName() + "())");
                        }
                    }
                }
            });
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "audit.log.fail-on-missing-template=true but the following @Audit template(s) could not be "
                            + "resolved by any configured AuditTemplateSource: " + missing);
        }
    }

    private Optional<String> findTemplate(String name) {
        for (AuditTemplateSource source : auditTemplateSources) {
            Optional<String> template = source.findTemplate(null, name);
            if (template.isPresent()) {
                return template;
            }
        }
        return Optional.empty();
    }
}
