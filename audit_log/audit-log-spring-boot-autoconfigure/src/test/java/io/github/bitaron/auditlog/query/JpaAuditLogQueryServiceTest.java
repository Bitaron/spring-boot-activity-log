package io.github.bitaron.auditlog.query;

import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import io.github.bitaron.auditlog.contract.AuditTenantResolver;
import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.entity.AuditOutcome;
import io.github.bitaron.auditlog.testfixtures.host.HostAppMarker;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP11 acceptance tests for {@link JpaAuditLogQueryService}: the max-page-size cap,
 * {@link Sort} whitelist, and keyset ({@link #findAfter}) pagination.
 */
class JpaAuditLogQueryServiceTest {

    private static final int SEED_ROW_COUNT = 5;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HostAppMarker.class)
            .withPropertyValues(
                    "spring.datasource.generate-unique-name=true",
                    "spring.jpa.hibernate.ddl-auto=create-drop",
                    "audit.log.query.max-page-size=10")
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    JpaRepositoriesAutoConfiguration.class,
                    AuditLogAutoConfiguration.class));

    @Test
    void oversizedPageSizeIsRejected() {
        contextRunner.run(context -> {
            AuditLogQueryService queryService = context.getBean(AuditLogQueryService.class);
            assertThatThrownBy(() -> queryService.find(AuditQuery.all(), PageRequest.of(0, 11)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("11")
                    .hasMessageContaining("audit.log.query.max-page-size");
        });
    }

    @Test
    void unrecognizedSortPropertyIsRejected() {
        contextRunner.run(context -> {
            AuditLogQueryService queryService = context.getBean(AuditLogQueryService.class);
            PageRequest pageable = PageRequest.of(0, 10, Sort.by("data"));
            assertThatThrownBy(() -> queryService.find(AuditQuery.all(), pageable))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("data");
        });
    }

    @Test
    void whitelistedSortPropertyOrdersResults() {
        contextRunner.run(context -> {
            seedRows(context, SEED_ROW_COUNT);
            AuditLogQueryService queryService = context.getBean(AuditLogQueryService.class);

            List<AuditRecord> ascending = queryService
                    .find(AuditQuery.all(), PageRequest.of(0, 10, Sort.by(Sort.Order.asc("createdAt"))))
                    .getContent();

            assertThat(ascending).hasSize(SEED_ROW_COUNT);
            assertThat(ascending).isSortedAccordingTo((a, b) -> a.createdAt().compareTo(b.createdAt()));
        });
    }

    @Test
    void findAfterRejectsALimitAboveTheConfiguredMaximum() {
        contextRunner.run(context -> {
            AuditLogQueryService queryService = context.getBean(AuditLogQueryService.class);
            assertThatThrownBy(() -> queryService.findAfter(AuditQuery.all(), null, 11))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void keysetPaginationReturnsStableNonOverlappingPagesAcrossTwoCalls() {
        contextRunner.run(context -> {
            seedRows(context, SEED_ROW_COUNT);
            AuditLogQueryService queryService = context.getBean(AuditLogQueryService.class);

            List<AuditRecord> firstPage = queryService.findAfter(AuditQuery.all(), null, 2);
            assertThat(firstPage).hasSize(2);

            AuditRecord last = firstPage.get(firstPage.size() - 1);
            List<AuditRecord> secondPage = queryService.findAfter(
                    AuditQuery.all(), new AuditCursor(last.createdAt(), last.id()), 2);
            assertThat(secondPage).hasSize(2);

            List<Long> firstPageIds = firstPage.stream().map(AuditRecord::id).toList();
            List<Long> secondPageIds = secondPage.stream().map(AuditRecord::id).toList();
            assertThat(secondPageIds).doesNotContainAnyElementsOf(firstPageIds);

            List<AuditRecord> thirdPage = queryService.findAfter(AuditQuery.all(),
                    new AuditCursor(secondPage.get(secondPage.size() - 1).createdAt(),
                            secondPage.get(secondPage.size() - 1).id()),
                    2);
            assertThat(thirdPage).hasSize(1); // 5 seeded rows: 2 + 2 + 1
        });
    }

    /**
     * WP15 acceptance: with the default {@code audit.log.multi-tenancy.enabled=false}, rows from
     * every tenant (including no tenant at all) are returned exactly as before this feature
     * existed - upgrading to this version changes nothing for a single-tenant deployment.
     */
    @Test
    void multiTenancyDisabledByDefaultReturnsRowsAcrossAllTenants() {
        contextRunner.run(context -> {
            seedRows(context, 2, "tenant-a");
            seedRows(context, 3, "tenant-b");
            AuditLogQueryService queryService = context.getBean(AuditLogQueryService.class);

            assertThat(queryService.find(AuditQuery.all(), PageRequest.of(0, 10)).getTotalElements())
                    .isEqualTo(5);
        });
    }

    /**
     * WP15 acceptance, the cross-tenant-isolation guarantee: once multi-tenancy is enabled, every
     * read is scoped to whatever {@link AuditTenantResolver} resolves - not to anything the caller
     * put in {@link AuditQuery} (which doesn't even have a tenant field) - so tenant-b's rows are
     * unreachable through this query service instance no matter how it's called.
     */
    @Test
    void multiTenancyEnabledScopesEveryReadToTheResolvedTenantOnly() {
        contextRunner.withPropertyValues("audit.log.multi-tenancy.enabled=true")
                .withUserConfiguration(FixedTenantResolverConfig.class)
                .run(context -> {
                    seedRows(context, 2, "tenant-a");
                    seedRows(context, 3, "tenant-b");
                    AuditLogQueryService queryService = context.getBean(AuditLogQueryService.class);

                    var page = queryService.find(AuditQuery.all(), PageRequest.of(0, 10));
                    assertThat(page.getTotalElements()).isEqualTo(2);
                    assertThat(page.getContent()).allSatisfy(record ->
                            assertThat(record.tenantId()).isEqualTo("tenant-a"));

                    List<AuditRecord> viaFindAfter = queryService.findAfter(AuditQuery.all(), null, 10);
                    assertThat(viaFindAfter).hasSize(2).allSatisfy(record ->
                            assertThat(record.tenantId()).isEqualTo("tenant-a"));
                });
    }

    /** WP15 acceptance, the fail-closed guarantee: an unresolvable tenant refuses the read
     * entirely rather than silently falling back to an unscoped (all-tenants) query. */
    @Test
    void multiTenancyEnabledWithNoResolvableTenantFailsClosed() {
        contextRunner.withPropertyValues("audit.log.multi-tenancy.enabled=true")
                .withUserConfiguration(NullTenantResolverConfig.class)
                .run(context -> {
                    seedRows(context, 2, "tenant-a");
                    AuditLogQueryService queryService = context.getBean(AuditLogQueryService.class);

                    assertThatThrownBy(() -> queryService.find(AuditQuery.all(), PageRequest.of(0, 10)))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("multi-tenancy");
                    assertThatThrownBy(() -> queryService.findAfter(AuditQuery.all(), null, 10))
                            .isInstanceOf(IllegalStateException.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedTenantResolverConfig {
        @Bean
        AuditTenantResolver auditTenantResolver() {
            return () -> "tenant-a";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NullTenantResolverConfig {
        @Bean
        AuditTenantResolver auditTenantResolver() {
            return () -> null;
        }
    }

    /** Seeds {@code count} rows with strictly increasing {@code createdAt} timestamps (bypassing
     * {@link io.github.bitaron.auditlog.core.AuditLogWriter}, which always stamps {@code now()},
     * so ordering across rows is deterministic for these tests), and no tenant. */
    private void seedRows(ApplicationContext context, int count) {
        seedRows(context, count, null);
    }

    private void seedRows(ApplicationContext context, int count, String tenantId) {
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        LocalDateTime base = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            EntityManager entityManager = context.getBean(EntityManager.class);
            List<AuditLog> rows = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                AuditLog auditLog = new AuditLog();
                auditLog.setAuditType("test");
                auditLog.setCreatedAt(base.plusMinutes(i));
                auditLog.setOutcome(AuditOutcome.SUCCESS);
                auditLog.setTenantId(tenantId);
                entityManager.persist(auditLog);
                rows.add(auditLog);
            }
        });
    }
}
