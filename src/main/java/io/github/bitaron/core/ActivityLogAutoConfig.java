package io.github.bitaron.core;

import io.github.bitaron.core.repository.ActivityLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackages = "io.github.bitaron.core.entity")
@EnableJpaRepositories(basePackages = "io.github.bitaron.core.repository")
public class ActivityLogAutoConfig {
    @Autowired
    ActivityLogMain activityLogMain;

    @Autowired
    ActivityLogRepository repository;

    @Bean
    @ConditionalOnMissingBean
    public ActivityLogAspect activityLogAspect() {
        return new ActivityLogAspect(activityLogMain, repository);
    }

}
