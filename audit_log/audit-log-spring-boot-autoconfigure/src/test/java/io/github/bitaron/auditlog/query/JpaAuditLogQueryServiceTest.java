package io.github.bitaron.auditlog.query;

import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
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

    /** Seeds {@code count} rows with strictly increasing {@code createdAt} timestamps (bypassing
     * {@link io.github.bitaron.auditlog.core.AuditLogWriter}, which always stamps {@code now()},
     * so ordering across rows is deterministic for these tests). */
    private void seedRows(ApplicationContext context, int count) {
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
                entityManager.persist(auditLog);
                rows.add(auditLog);
            }
        });
    }
}
