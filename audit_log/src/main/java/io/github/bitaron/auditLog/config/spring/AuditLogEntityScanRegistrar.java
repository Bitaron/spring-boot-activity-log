package io.github.bitaron.auditLog.config.spring;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.domain.EntityScanPackages;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers this starter's own {@code io.github.bitaron.auditLog.entity} package for JPA entity
 * scanning, without ever narrowing what the host application itself scans.
 * <p>
 * A naive {@code @EntityScan("io.github.bitaron.auditLog.entity")} on the auto-configuration is
 * an active regression: {@link EntityScanPackages#register} only merges with an
 * <em>existing</em> registration. If ours is the first (and typically only) {@code @EntityScan}
 * anywhere in the application - the common case, since most Spring Boot apps never declare one
 * and instead rely on the implicit default of scanning the {@code @SpringBootApplication}
 * package - registering just our own package here would silently stop the host's own entities
 * from being scanned at all. This registrar reads the host's default base package(s) via the
 * (public, stable) {@link AutoConfigurationPackages} API first and folds them in, so the
 * resulting registration is always a strict superset of what would have been scanned without
 * this starter on the classpath.
 */
class AuditLogEntityScanRegistrar implements ImportBeanDefinitionRegistrar, BeanFactoryAware {

    static final String AUDIT_ENTITY_PACKAGE = "io.github.bitaron.auditLog.entity";

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        List<String> packages = new ArrayList<>();
        if (beanFactory != null && AutoConfigurationPackages.has(beanFactory)) {
            packages.addAll(AutoConfigurationPackages.get(beanFactory));
        }
        packages.add(AUDIT_ENTITY_PACKAGE);
        EntityScanPackages.register(registry, packages);
    }
}
