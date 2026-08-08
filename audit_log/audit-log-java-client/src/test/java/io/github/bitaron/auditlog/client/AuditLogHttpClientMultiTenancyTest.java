package io.github.bitaron.auditlog.client;

import io.github.bitaron.auditlog.server.proto.v1.AuditCursorQueryRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP17 acceptance, from the client's own perspective (the server-side guarantee is already
 * covered by {@code AuditLogServerMultiTenancyIntegrationTest}): a client constructed with one
 * tenant's API key never sees another tenant's records via {@link AuditLogHttpClient#query} or
 * {@link AuditLogHttpClient#queryAfter}, no matter how it queries - tenant identity comes from the
 * key alone, never from anything the client sends.
 */
@SpringBootTest(
        classes = ClientTestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.generate-unique-name=true",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "audit.log.server.enabled=true",
                "audit.log.multi-tenancy.enabled=true",
                "audit.log.server.api-keys.tenant-a=key-a",
                "audit.log.server.api-keys.tenant-b=key-b"
        })
class AuditLogHttpClientMultiTenancyTest {

    @LocalServerPort
    private int port;

    @Test
    void eachTenantsClientOnlyEverSeesItsOwnDataViaQuery() {
        AuditLogHttpClient clientA = new AuditLogHttpClient("http://localhost:" + port, "key-a");
        AuditLogHttpClient clientB = new AuditLogHttpClient("http://localhost:" + port, "key-b");

        clientA.ingest(AuditEventRequest.newBuilder().setAuditType("client-tenant-isolation").build());
        clientB.ingest(AuditEventRequest.newBuilder().setAuditType("client-tenant-isolation").build());

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var fromA = clientA.query(AuditQueryRequest.newBuilder()
                    .setAuditType("client-tenant-isolation").setSize(10).build());
            assertThat(fromA.getRecordsList()).hasSize(1);
            assertThat(fromA.getRecords(0).getTenantId()).isEqualTo("tenant-a");

            var fromB = clientB.query(AuditQueryRequest.newBuilder()
                    .setAuditType("client-tenant-isolation").setSize(10).build());
            assertThat(fromB.getRecordsList()).hasSize(1);
            assertThat(fromB.getRecords(0).getTenantId()).isEqualTo("tenant-b");
        });
    }

    @Test
    void eachTenantsClientOnlyEverSeesItsOwnDataViaQueryAfter() {
        AuditLogHttpClient clientA = new AuditLogHttpClient("http://localhost:" + port, "key-a");
        AuditLogHttpClient clientB = new AuditLogHttpClient("http://localhost:" + port, "key-b");

        clientA.ingest(AuditEventRequest.newBuilder().setAuditType("client-tenant-isolation-cursor").build());
        clientB.ingest(AuditEventRequest.newBuilder().setAuditType("client-tenant-isolation-cursor").build());
        clientB.ingest(AuditEventRequest.newBuilder().setAuditType("client-tenant-isolation-cursor").build());

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var fromA = clientA.queryAfter(AuditCursorQueryRequest.newBuilder()
                    .setAuditType("client-tenant-isolation-cursor").setLimit(10).build());
            assertThat(fromA.getRecordsList()).hasSize(1);

            var fromB = clientB.queryAfter(AuditCursorQueryRequest.newBuilder()
                    .setAuditType("client-tenant-isolation-cursor").setLimit(10).build());
            assertThat(fromB.getRecordsList()).hasSize(2);
        });
    }
}
