package org.commlink.log;

import io.github.bitaron.auditlog.entity.AuditOutcome;
import io.github.bitaron.auditlog.query.AuditLogQueryService;
import io.github.bitaron.auditlog.query.AuditQuery;
import io.github.bitaron.auditlog.query.AuditRecord;
import io.github.bitaron.auditlog.testsupport.AuditLogAssertions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that the audit-log starter works inside a real Spring Boot application: the
 * demo's own entities/repositories and the starter's must both come up, {@code /test} must
 * succeed, and - once the (asynchronous) write lands - exactly one audit_log row must exist.
 * <p>
 * Reads through {@link AuditLogQueryService} (via {@link AuditLogAssertions}, WP17) rather than
 * querying the {@code AuditLog}/{@code AuditLogMessage} entities directly - this test previously
 * did the latter, contradicting this project's own README ("don't query the entities directly").
 * {@link AuditLogAssertions#messagesFor} is the one deliberate exception: the read API has no
 * message-content accessor yet (a separate, future work item), so that one piece still goes
 * through the entity manager, centralized in the shared helper instead of ad hoc here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AuditLogTestControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditLogQueryService auditLogQueryService;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearAuditLogs() {
        AuditLogAssertions.clearAuditLog(entityManager, transactionManager);
    }

    @Test
    void successfulCallIsRecordedAsynchronously() throws Exception {
        mockMvc.perform(get("/test")).andExpect(status().isOk());

        AuditRecord record = AuditLogAssertions.awaitRecord(
                auditLogQueryService, AuditQuery.byType("test"), Duration.ofSeconds(5));

        assertThat(record.auditType()).isEqualTo("test");
        assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(record.durationMs()).isNotNull();
        assertThat(messagesFor(record)).allMatch(m -> m.contains("got value 10"));
    }

    /**
     * A real servlet container (verified manually against the running app) turns this uncaught
     * exception into an HTTP 500 - the business failure is untouched by the starter, which is
     * the whole point. MockMvc's mock dispatcher, unlike a real container, re-throws an uncaught
     * controller exception to the test instead of translating it into a response, so the
     * assertion here is on that propagation and on the audit record it leaves behind.
     */
    @Test
    void failingCallStillPropagatesAndIsRecorded() {
        assertThatThrownBy(() -> mockMvc.perform(get("/test/fail")))
                .hasRootCauseMessage("Test exception");

        AuditRecord record = AuditLogAssertions.awaitRecord(
                auditLogQueryService, AuditQuery.byType("test"), Duration.ofSeconds(5));

        assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(messagesFor(record)).allMatch(m -> m.contains("it failed"));
    }

    private List<String> messagesFor(AuditRecord record) {
        return AuditLogAssertions.messagesFor(entityManager, record);
    }
}
