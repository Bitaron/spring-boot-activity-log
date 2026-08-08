package io.github.bitaron.auditlog.client;

import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditRecordProto;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP17 acceptance: {@link AuditRecordProtos#createdAt} round-trips through a real ingest/query
 * call - the parsed value matches what was actually recorded, not just a hand-built proto.
 */
@SpringBootTest(
        classes = ClientTestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.generate-unique-name=true",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "audit.log.server.enabled=true",
                "audit.log.multi-tenancy.enabled=true",
                "audit.log.server.api-keys.client-test-tenant=client-test-key"
        })
class AuditRecordProtosTest {

    @LocalServerPort
    private int port;

    @Test
    void createdAtParsesTheWireStringBackToALocalDateTime() {
        AuditLogHttpClient client = new AuditLogHttpClient("http://localhost:" + port, "client-test-key");
        LocalDateTime before = LocalDateTime.now();

        client.ingest(AuditEventRequest.newBuilder()
                .setAuditType("created-at-round-trip")
                .setActorId("created-at-actor")
                .build());

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var response = client.query(AuditQueryRequest.newBuilder()
                    .setAuditType("created-at-round-trip")
                    .setPage(0)
                    .setSize(10)
                    .build());
            assertThat(response.getRecordsList()).hasSize(1);

            LocalDateTime createdAt = AuditRecordProtos.createdAt(response.getRecords(0));
            assertThat(createdAt).isNotNull().isAfterOrEqualTo(before.minusSeconds(1));
        });
    }

    @Test
    void createdAtReturnsNullForAnUnsetRecord() {
        assertThat(AuditRecordProtos.createdAt(AuditRecordProto.newBuilder().build())).isNull();
    }
}
