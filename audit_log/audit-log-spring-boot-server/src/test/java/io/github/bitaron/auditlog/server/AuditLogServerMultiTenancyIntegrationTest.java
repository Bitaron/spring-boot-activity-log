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
 * WP16 acceptance tests for real per-tenant server authentication: two tenants, each with its own
 * {@code audit.log.server.api-keys.<tenantId>} secret. Kept as a separate test class from
 * {@link AuditLogServerIntegrationTest} since that class deliberately configures only one tenant.
 * <p>
 * The core guarantee under test: which tenant a request acts as is determined entirely by which
 * API key it presents - never by anything the caller puts in a header or request body - so a
 * caller holding tenant-a's key can never read or write tenant-b's data, and vice versa.
 */
@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.generate-unique-name=true",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "audit.log.server.enabled=true",
                "audit.log.multi-tenancy.enabled=true",
                "audit.log.server.api-keys.tenant-a=key-a",
                "audit.log.server.api-keys.tenant-b=key-b"
        })
@AutoConfigureMockMvc
class AuditLogServerMultiTenancyIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";

    @Autowired
    private MockMvc mockMvc;

    /**
     * The cross-tenant-isolation guarantee, over real HTTP: events ingested under tenant-a's and
     * tenant-b's own keys (neither request body ever names a tenant - it's authenticated from the
     * key alone) stay isolated when queried back - a caller holding only tenant-a's key can never
     * see tenant-b's row, no matter how it queries, and vice versa.
     */
    @Test
    void eachTenantsKeyOnlyEverSeesItsOwnData() throws Exception {
        ingest("key-a", "tenant-scoped-event");
        ingest("key-b", "tenant-scoped-event");

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mockMvc.perform(get("/audit-log/records")
                                .header(API_KEY_HEADER, "key-a")
                                .param("auditType", "tenant-scoped-event")
                                .accept("application/json"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value("1"))
                        .andExpect(jsonPath("$.records[0].tenantId").value("tenant-a")));

        mockMvc.perform(get("/audit-log/records")
                        .header(API_KEY_HEADER, "key-b")
                        .param("auditType", "tenant-scoped-event")
                        .accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value("1"))
                .andExpect(jsonPath("$.records[0].tenantId").value("tenant-b"));
    }

    /** A key that doesn't match any configured tenant is rejected, same as no key at all. */
    @Test
    void queryWithAnUnrecognizedKeyIsRejected() throws Exception {
        mockMvc.perform(get("/audit-log/records")
                        .header(API_KEY_HEADER, "not-a-configured-key")
                        .accept("application/json"))
                .andExpect(status().isUnauthorized());
    }

    /** tenant-a's key cannot be used to write data that claims to be tenant-b's. */
    @Test
    void tenantAsKeyCannotIngestAsTenantB() throws Exception {
        AuditEventRequest request = AuditEventRequest.newBuilder()
                .setAuditType("cross-tenant-attempt")
                .setTenantId("tenant-b")
                .build();

        mockMvc.perform(post("/audit-log/events")
                        .header(API_KEY_HEADER, "key-a")
                        .contentType("application/x-protobuf")
                        .content(request.toByteArray()))
                .andExpect(status().isBadRequest());
    }

    private void ingest(String apiKey, String auditType) throws Exception {
        AuditEventRequest request = AuditEventRequest.newBuilder()
                .setAuditType(auditType)
                .build(); // no tenant_id on the wire - authenticated from apiKey alone, see class javadoc
        mockMvc.perform(post("/audit-log/events")
                        .header(API_KEY_HEADER, apiKey)
                        .contentType("application/x-protobuf")
                        .content(request.toByteArray()))
                .andExpect(status().isAccepted());
    }
}
