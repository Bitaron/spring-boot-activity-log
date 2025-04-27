package io.github.bitaron.auditLog.config.spring;

import io.github.bitaron.auditLog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditLog.contract.TemplateResolver;
import io.github.bitaron.auditLog.core.AuditLogAspect;
import io.github.bitaron.auditLog.properties.AuditLogProperties;
import io.github.bitaron.auditLog.repository.AuditGroupRepository;
import io.github.bitaron.auditLog.repository.AuditLogRepository;
import io.github.bitaron.auditLog.repository.AuditTemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@AutoConfiguration
@EnableAsync
@EntityScan(basePackages = "io.github.bitaron.auditLog.entity")
@EnableJpaRepositories(basePackages = "io.github.bitaron.auditLog.repository")
public class AuditLogSpringBootAutoConfig {
    @Autowired
    AuditLogGenericDataGetter auditLogGenericDataGetter;


    @Autowired
    TemplateResolver templateResolver;

    @Autowired
    AuditLogRepository repository;

    @Autowired
    AuditTemplateRepository auditTemplateRepository;

    @Autowired
    AuditGroupRepository auditGroupRepository;

    @Autowired
    AuditLogProperties auditLogProperties;

    @Bean
    @ConditionalOnMissingBean
    public AuditLogAspect activityLogAspect() {
        return new AuditLogAspect(auditLogProperties, auditLogGenericDataGetter,
                repository, auditTemplateRepository, auditGroupRepository, templateResolver);
    }

}
