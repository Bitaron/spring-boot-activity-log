package io.github.bitaron.auditLog.config.spring;

import io.github.bitaron.auditLog.contract.ActivityLogMain;
import io.github.bitaron.auditLog.core.AuditLogAspect;
import io.github.bitaron.auditLog.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackages = "io.github.bitaron.core.entity")
@EnableJpaRepositories(basePackages = "io.github.bitaron.core.repository")
public class AuditLogSpringBootAutoConfig {
    @Autowired
    ActivityLogMain activityLogMain;

    @Autowired
    AuditLogRepository repository;

    @Bean
    @ConditionalOnMissingBean
    public AuditLogAspect activityLogAspect() {
        return new AuditLogAspect(activityLogMain, repository);
    }

}
