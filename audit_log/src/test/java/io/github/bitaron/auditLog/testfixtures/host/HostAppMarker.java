package io.github.bitaron.auditLog.testfixtures.host;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Configuration;

/**
 * Registers this package as the "base package" via {@code AutoConfigurationPackages} - exactly
 * what {@code @SpringBootApplication} does for a real application's main class - without pulling
 * in a full {@code @SpringBootApplication}. Used by {@code ApplicationContextRunner} tests to
 * simulate a host application that never declares its own {@code @EntityScan} or
 * {@code @EnableJpaRepositories}, which is the common case this starter must not break.
 */
@AutoConfigurationPackage
@Configuration(proxyBeanMethods = false)
public class HostAppMarker {
}
