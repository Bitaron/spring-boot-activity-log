package io.github.bitaron.auditlog.testsupport;

import io.github.bitaron.auditlog.query.AuditLogQueryService;
import io.github.bitaron.auditlog.query.AuditQuery;
import io.github.bitaron.auditlog.query.AuditRecord;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Test helpers for asserting that an audit record was written, through the same
 * {@link AuditLogQueryService} a consumer is meant to use in production code - not by reaching
 * into the {@code AuditLog}/{@code AuditLogMessage} JPA entities directly, which the module's own
 * README specifically advises against.
 * <p>
 * Deliberately in a package of its own ({@code testsupport}, not the module's internal
 * {@code testfixtures} scaffolding) and published as this module's {@code test-jar} classifier -
 * see the {@code maven-jar-plugin} execution in this module's {@code pom.xml} - so a consuming
 * application's tests can depend on exactly this, and nothing else from this module's own
 * internal test suite.
 * <p>
 * {@code ASYNC} is this starter's default delivery mode, so a write is fire-and-forget from the
 * caller's perspective - the {@code await*} methods here poll {@link AuditLogQueryService#find}
 * until the expected record(s) show up (or a timeout elapses), the same pattern this project's own
 * tests use via Awaitility, without requiring a consumer to add that dependency just to assert an
 * audit record was written.
 */
public final class AuditLogAssertions {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private AuditLogAssertions() {
    }

    /** {@link #awaitRecord(AuditLogQueryService, AuditQuery, Duration)} with a 5-second timeout. */
    public static AuditRecord awaitRecord(AuditLogQueryService queryService, AuditQuery query) {
        return awaitRecord(queryService, query, DEFAULT_TIMEOUT);
    }

    /** Waits for exactly (at least) one record matching {@code query} and returns the first one -
     * for the common case of asserting on a single audited call's outcome. */
    public static AuditRecord awaitRecord(AuditLogQueryService queryService, AuditQuery query, Duration timeout) {
        return awaitRecords(queryService, query, 1, timeout).get(0);
    }

    /** {@link #awaitRecords(AuditLogQueryService, AuditQuery, int, Duration)} with a 5-second timeout. */
    public static List<AuditRecord> awaitRecords(AuditLogQueryService queryService, AuditQuery query, int expectedCount) {
        return awaitRecords(queryService, query, expectedCount, DEFAULT_TIMEOUT);
    }

    /**
     * Polls {@link AuditLogQueryService#find} until at least {@code expectedCount} records
     * matching {@code query} are found, or {@code timeout} elapses.
     *
     * @throws AssertionError if {@code expectedCount} records never appear within {@code timeout}
     */
    public static List<AuditRecord> awaitRecords(AuditLogQueryService queryService, AuditQuery query,
                                                  int expectedCount, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        List<AuditRecord> lastSeen = List.of();
        while (true) {
            lastSeen = queryService.find(query, PageRequest.of(0, Math.max(expectedCount, 1))).getContent();
            if (lastSeen.size() >= expectedCount) {
                return lastSeen;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Expected at least " + expectedCount + " audit record(s) matching "
                        + query + " within " + timeout + ", but found " + lastSeen.size());
            }
            sleep(POLL_INTERVAL);
        }
    }

    /**
     * Reads the rendered message text for {@code record}. {@link AuditLogQueryService} has no
     * message-read API yet (a separate, future work item - see AGENTS.md), so this is a deliberate,
     * centralized exception to the "don't query the entities directly" rule above: one place doing
     * it, instead of every consumer's test reaching for its own raw JPQL.
     */
    public static List<String> messagesFor(EntityManager entityManager, AuditRecord record) {
        return entityManager.createQuery(
                        "select m.message from AuditLogMessage m where m.auditLogId = :id", String.class)
                .setParameter("id", record.id())
                .getResultList();
    }

    /** Deletes every {@code AuditLog}/{@code AuditLogMessage} row - for per-test cleanup, run
     * inside its own transaction since most callers won't already be in one. */
    public static void clearAuditLog(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("delete from AuditLogMessage").executeUpdate();
            entityManager.createQuery("delete from AuditLog").executeUpdate();
        });
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting audit records", e);
        }
    }
}
