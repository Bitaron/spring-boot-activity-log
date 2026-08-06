package io.github.bitaron.auditlog.server;

import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP15 acceptance tests for the server module's multi-tenancy threading - kept as a separate test
 * class from {@link AuditLogServerIntegrationTest} since the two multi-tenancy switches
 * ({@code audit.log.multi-tenancy.enabled} and {@code audit.log.server.multi-tenancy.required})
 * need to be on here but must stay off for that class's tests to keep testing today's default
 * (non-multi-tenant) behavior.
 */
@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.generate-unique-name=true",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "audit.log.server.enabled=true",
                "audit.log.server.api-key=test-api-key",
                "audit.log.multi-tenancy.enabled=true",
                "audit.log.server.multi-tenancy.required=true"
        })
@AutoConfigureMockMvc
class AuditLogServerMultiTenancyIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY = "test-api-key";
    private static final String TENANT_HEADER = "X-TENANT-ID";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ingestWithoutTenantIdIsRejectedWhenRequired() throws Exception {
        AuditEventRequest request = AuditEventRequest.newBuilder()
                .setAuditType("no-tenant-event")
                .build();

        mockMvc.perform(post("/audit-log/events")
                        .header(API_KEY_HEADER, API_KEY)
                        .contentType("application/x-protobuf")
                        .content(request.toByteArray()))
                .andExpect(status().isBadRequest());
    }

    /**
     * The cross-tenant-isolation guarantee, over real HTTP: two tenants' events are ingested
     * (tenant carried explicitly via the wire {@code tenant_id} field, since ingest has no ambient
     * request-scoped tenant of its own to resolve), then a read scoped by the {@code X-TENANT-ID}
     * header via the default {@code AuditTenantResolver} sees only the matching tenant's rows -
     * never the other tenant's, no matter which header value is sent.
     */
    @Test
    void queryIsScopedToTheTenantIdHeader() throws Exception {
        ingest("tenant-scoped-event", "tenant-a");
        ingest("tenant-scoped-event", "tenant-b");

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mockMvc.perform(get("/audit-log/records")
                                .header(API_KEY_HEADER, API_KEY)
                                .header(TENANT_HEADER, "tenant-a")
                                .param("auditType", "tenant-scoped-event")
                                .accept("application/json"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value("1"))
                        .andExpect(jsonPath("$.records[0].tenantId").value("tenant-a")));

        mockMvc.perform(get("/audit-log/records")
                        .header(API_KEY_HEADER, API_KEY)
                        .header(TENANT_HEADER, "tenant-b")
                        .param("auditType", "tenant-scoped-event")
                        .accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value("1"))
                .andExpect(jsonPath("$.records[0].tenantId").value("tenant-b"));
    }

    /** No {@code X-TENANT-ID} header at all - the fail-closed guarantee applies over HTTP too. */
    @Test
    void queryWithNoTenantHeaderIsRejected() throws Exception {
        mockMvc.perform(get("/audit-log/records")
                        .header(API_KEY_HEADER, API_KEY)
                        .accept("application/json"))
                .andExpect(status().isBadRequest());
    }

    private void ingest(String auditType, String tenantId) throws Exception {
        AuditEventRequest request = AuditEventRequest.newBuilder()
                .setAuditType(auditType)
                .setTenantId(tenantId)
                .build();
        mockMvc.perform(post("/audit-log/events")
                        .header(API_KEY_HEADER, API_KEY)
                        .contentType("application/x-protobuf")
                        .content(request.toByteArray()))
                .andExpect(status().isAccepted());
    }
}
