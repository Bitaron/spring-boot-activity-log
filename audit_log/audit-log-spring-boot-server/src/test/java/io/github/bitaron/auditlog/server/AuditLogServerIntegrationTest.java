package io.github.bitaron.auditlog.server;

import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import jakarta.persistence.EntityManager;
import org.awaitility.Awaitility;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP13 acceptance test: {@code POST /audit-log/events} persists a row with a valid API key and
 * rejects the request without one; {@code GET /audit-log/records} returns it back. Exercises both
 * the JSON-fallback path (easiest to hand-write for a test/curl) and the binary
 * {@code application/x-protobuf} path, proving the same {@code ProtobufHttpMessageConverter}
 * handles both.
 */
@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.generate-unique-name=true",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "audit.log.server.enabled=true",
                "audit.log.server.api-key=test-api-key"
        })
@AutoConfigureMockMvc
class AuditLogServerIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY = "test-api-key";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void ingestWithoutApiKeyIsRejected() throws Exception {
        mockMvc.perform(post("/audit-log/events")
                        .contentType("application/json")
                        .content("{\"auditType\":\"remote-event\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ingestViaJsonPersistsAndIsVisibleThroughQuery() throws Exception {
        mockMvc.perform(post("/audit-log/events")
                        .header(API_KEY_HEADER, API_KEY)
                        .contentType("application/json")
                        .accept("application/json")
                        .content("{\"auditType\":\"remote-event-json\",\"actorId\":\"remote-actor\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(findByAuditType("remote-event-json")).hasSize(1));

        mockMvc.perform(get("/audit-log/records")
                        .header(API_KEY_HEADER, API_KEY)
                        .param("auditType", "remote-event-json")
                        .accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].actorId").value("remote-actor"))
                // int64 fields (totalElements) render as JSON strings under Protobuf's canonical
                // JSON mapping (avoids precision loss for large values in JS clients) - "1", not 1.
                .andExpect(jsonPath("$.totalElements").value("1"));
    }

    @Test
    void ingestViaBinaryProtobufPersists() throws Exception {
        AuditEventRequest request = AuditEventRequest.newBuilder()
                .setAuditType("remote-event-binary")
                .setActorId("binary-actor")
                .build();

        mockMvc.perform(post("/audit-log/events")
                        .header(API_KEY_HEADER, API_KEY)
                        .contentType("application/x-protobuf")
                        .content(request.toByteArray()))
                .andExpect(status().isAccepted());

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(findByAuditType("remote-event-binary")).hasSize(1));
    }

    private List<AuditLog> findByAuditType(String auditType) {
        return new TransactionTemplate(transactionManager).execute(status ->
                entityManager.createQuery("select a from AuditLog a where a.auditType = :t", AuditLog.class)
                        .setParameter("t", auditType)
                        .getResultList());
    }
}
