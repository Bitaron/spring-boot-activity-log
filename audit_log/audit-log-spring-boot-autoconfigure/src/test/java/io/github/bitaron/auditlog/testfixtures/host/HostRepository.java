package io.github.bitaron.auditlog.testfixtures.host;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Stands in for a Spring Data repository belonging to a hypothetical consuming application.
 * Never declared or scanned by the starter itself - only Spring Boot's own default
 * {@code JpaRepositoriesAutoConfiguration}, driven by {@code @AutoConfigurationPackage} on
 * {@link HostAppMarker}, should pick this up.
 */
public interface HostRepository extends JpaRepository<HostEntity, Long> {
}
