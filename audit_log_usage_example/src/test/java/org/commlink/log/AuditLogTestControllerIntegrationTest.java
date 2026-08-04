package org.commlink.log;

import io.github.bitaron.auditlog.entity.AuditLog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AuditLogTestControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearAuditLogs() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createQuery("delete from AuditLog").executeUpdate());
    }

    @Test
    void successfulCallIsRecordedAsynchronously() throws Exception {
        mockMvc.perform(get("/test")).andExpect(status().isOk());

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(auditLogs()).hasSize(1));

        AuditLog row = auditLogs().get(0);
        assertThat(row.getAuditType()).isEqualTo("test");
        assertThat(row.getMessage()).contains("got value 10");
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

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(auditLogs()).hasSize(1));

        assertThat(auditLogs().get(0).getMessage()).contains("it failed");
    }

    private List<AuditLog> auditLogs() {
        return new TransactionTemplate(transactionManager).execute(status ->
                entityManager.createQuery("select a from AuditLog a", AuditLog.class).getResultList());
    }
}
