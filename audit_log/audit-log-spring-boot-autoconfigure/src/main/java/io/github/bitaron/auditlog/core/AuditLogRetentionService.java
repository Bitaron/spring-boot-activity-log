package io.github.bitaron.auditlog.core;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Scheduled deletion of {@code AuditLog}/{@code AuditLogMessage} rows older than a configured
 * retention window - off by default ({@code audit.log.retention.enabled=false}), since deleting
 * audit history is a decision this starter must never make for a consuming application unasked.
 * <p>
 * Owns a dedicated {@link ThreadPoolTaskScheduler} of its own rather than relying on
 * {@code @Scheduled}/{@code @EnableScheduling}: turning on scheduling support is a context-wide,
 * consumer-visible behavior change this starter should not impose - the same reasoning
 * {@code AuditLogTaskExecutor} already applies to {@code @EnableAsync}/{@code @Async}.
 * <p>
 * Deletes in bounded batches, oldest row first, via {@link TransactionTemplate} (not a
 * {@code @Transactional} method on this class - self-invocation from {@link #runOnce} would
 * silently bypass a proxy-based transaction the same way {@code AuditLogWriter}'s javadoc warns
 * against), rather than one unbounded {@code DELETE} that could hold a long lock on
 * {@code audit_log} while a large backlog is cleared.
 */
@Slf4j
public class AuditLogRetentionService implements InitializingBean, DisposableBean {

    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final Duration maxAge;
    private final String cron;
    private final int batchSize;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    public AuditLogRetentionService(EntityManager entityManager, PlatformTransactionManager transactionManager,
                                     Duration maxAge, String cron, int batchSize) {
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxAge = maxAge;
        this.cron = cron;
        this.batchSize = batchSize;
    }

    @Override
    public void afterPropertiesSet() {
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("audit-log-retention-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        scheduler.schedule(this::runOnce, new CronTrigger(cron));
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }

    /**
     * Runs one purge pass immediately, outside the cron schedule - looping over bounded batches
     * until a batch comes back smaller than {@link #batchSize} (i.e. nothing older than the
     * cutoff remains). Exposed as {@code public} for tests and for applications that want to
     * trigger a purge on demand (e.g. from an admin endpoint) rather than waiting for the schedule.
     */
    public void runOnce() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(maxAge);
        int totalDeleted = 0;
        int deletedThisBatch;
        do {
            deletedThisBatch = purgeOldestBatch(cutoff);
            totalDeleted += deletedThisBatch;
        } while (deletedThisBatch == batchSize);
        if (totalDeleted > 0) {
            log.info("audit-log retention: purged {} record(s) older than {}", totalDeleted, cutoff);
        }
    }

    private int purgeOldestBatch(LocalDateTime cutoff) {
        Integer deleted = transactionTemplate.execute(status -> {
            List<Long> ids = entityManager.createQuery(
                            "select a.id from AuditLog a where a.createdAt < :cutoff order by a.createdAt asc",
                            Long.class)
                    .setParameter("cutoff", cutoff)
                    .setMaxResults(batchSize)
                    .getResultList();
            if (ids.isEmpty()) {
                return 0;
            }
            deleteByAuditLogIds(ids);
            return ids.size();
        });
        return deleted == null ? 0 : deleted;
    }

    private void deleteByAuditLogIds(List<Long> ids) {
        Query deleteMessages = entityManager.createQuery("delete from AuditLogMessage m where m.auditLogId in :ids");
        deleteMessages.setParameter("ids", ids);
        deleteMessages.executeUpdate();

        Query deleteLogs = entityManager.createQuery("delete from AuditLog a where a.id in :ids");
        deleteLogs.setParameter("ids", ids);
        deleteLogs.executeUpdate();
    }
}
