package io.github.bitaron.auditlog.server;

import com.jayway.jsonpath.JsonPath;
import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import jakarta.persistence.EntityManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 * WP13/WP16 acceptance test: {@code POST /audit-log/events} persists a row tagged with whichever
 * tenant the supplied API key authenticates (see {@link ApiKeyAuthFilter}/
 * {@link ApiKeyAuditTenantResolver}), rejects the request without a valid key, and
 * {@code GET /audit-log/records} returns it back, scoped to that same tenant. Exercises both the
 * JSON-fallback path (easiest to hand-write for a test/curl) and the binary
 * {@code application/x-protobuf} path, proving the same {@code ProtobufHttpMessageConverter}
 * handles both. Single-tenant config here (one key); see
 * {@link AuditLogServerMultiTenancyIntegrationTest} for the cross-tenant-isolation guarantee with
 * more than one.
 */
@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.generate-unique-name=true",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "audit.log.server.enabled=true",
                "audit.log.multi-tenancy.enabled=true",
                "audit.log.server.api-keys.tenant-a=test-api-key"
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
    void ingestWithAnUnknownApiKeyIsRejected() throws Exception {
        mockMvc.perform(post("/audit-log/events")
                        .header(API_KEY_HEADER, "not-a-configured-key")
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
                // (WP16) tenant_id was never set on the request body - it's authenticated from
                // the API key, not caller-suppliable.
                .andExpect(jsonPath("$.records[0].tenantId").value("tenant-a"))
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

    /** WP16 acceptance: the persisted tenant is the one the API key authenticated, not whatever
     * (if anything) the request body's {@code tenant_id} says. */
    @Test
    void ingestPersistsTheAuthenticatedTenantRegardlessOfWireTenantId() throws Exception {
        AuditEventRequest request = AuditEventRequest.newBuilder()
                .setAuditType("remote-event-tenant")
                .setActorId("tenant-actor")
                .build(); // no tenant_id set on the wire at all

        mockMvc.perform(post("/audit-log/events")
                        .header(API_KEY_HEADER, API_KEY)
                        .contentType("application/x-protobuf")
                        .content(request.toByteArray()))
                .andExpect(status().isAccepted());

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(findByAuditType("remote-event-tenant"))
                        .extracting(AuditLog::getTenantId).containsExactly("tenant-a"));
    }

    /** WP16 acceptance: a request body naming a *different* tenant than the one authenticated is
     * rejected outright, not silently overridden or accepted. */
    @Test
    void ingestWithMismatchedTenantIdOnTheWireIsRejected() throws Exception {
        AuditEventRequest request = AuditEventRequest.newBuilder()
                .setAuditType("remote-event-mismatch")
                .setTenantId("some-other-tenant")
                .build();

        mockMvc.perform(post("/audit-log/events")
                        .header(API_KEY_HEADER, API_KEY)
                        .contentType("application/x-protobuf")
                        .content(request.toByteArray()))
                .andExpect(status().isBadRequest());
    }

    /**
     * WP17 acceptance: {@code GET /audit-log/records/after} keyset-paginates the same way
     * {@link io.github.bitaron.auditlog.query.AuditLogQueryService#findAfter} does in-process -
     * walking two pages of 2 with a 3-row result set returns 2 then 1, with no overlap.
     */
    @Test
    void queryAfterWalksPagesViaKeysetPagination() throws Exception {
        ingest("cursor-pagination-event", "actor-1");
        ingest("cursor-pagination-event", "actor-2");
        ingest("cursor-pagination-event", "actor-3");

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(findByAuditType("cursor-pagination-event")).hasSize(3));

        String firstPageBody = mockMvc.perform(get("/audit-log/records/after")
                        .header(API_KEY_HEADER, API_KEY)
                        .param("auditType", "cursor-pagination-event")
                        .param("limit", "2")
                        .accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        String lastCreatedAt = JsonPath.read(firstPageBody, "$.records[1].createdAt");
        String lastId = String.valueOf((Object) JsonPath.read(firstPageBody, "$.records[1].id"));

        mockMvc.perform(get("/audit-log/records/after")
                        .header(API_KEY_HEADER, API_KEY)
                        .param("auditType", "cursor-pagination-event")
                        .param("cursorCreatedAt", lastCreatedAt)
                        .param("cursorId", lastId)
                        .param("limit", "2")
                        .accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(1));
    }

    /** A cursor with only one of the two required fields is rejected, rather than silently
     * treated as "first page" or "no lower bound". */
    @Test
    void queryAfterRejectsAHalfSuppliedCursor() throws Exception {
        mockMvc.perform(get("/audit-log/records/after")
                        .header(API_KEY_HEADER, API_KEY)
                        .param("cursorId", "1")
                        .accept("application/json"))
                .andExpect(status().isBadRequest());
    }

    private void ingest(String auditType, String actorId) throws Exception {
        AuditEventRequest request = AuditEventRequest.newBuilder()
                .setAuditType(auditType)
                .setActorId(actorId)
                .build();
        mockMvc.perform(post("/audit-log/events")
                        .header(API_KEY_HEADER, API_KEY)
                        .contentType("application/x-protobuf")
                        .content(request.toByteArray()))
                .andExpect(status().isAccepted());
    }

    private List<AuditLog> findByAuditType(String auditType) {
        return new TransactionTemplate(transactionManager).execute(status ->
                entityManager.createQuery("select a from AuditLog a where a.auditType = :t", AuditLog.class)
                        .setParameter("t", auditType)
                        .getResultList());
    }
}
