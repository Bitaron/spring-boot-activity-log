package io.github.bitaron.auditlog.client;

import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP14 acceptance test: starts the real {@code audit-log-spring-boot-server} module at a random
 * port and round-trips one {@link AuditLogHttpClient#ingest} + one
 * {@link AuditLogHttpClient#query} call through the generated Protobuf types - no manual
 * (de)serialization code anywhere in this test.
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
class AuditLogHttpClientTest {

    @LocalServerPort
    private int port;

    @Test
    void ingestThenQueryRoundTripsThroughGeneratedTypes() {
        AuditLogHttpClient client = new AuditLogHttpClient("http://localhost:" + port, "client-test-key");

        AuditEventResponse ingestResponse = client.ingest(AuditEventRequest.newBuilder()
                .setAuditType("client-round-trip")
                .setActorId("client-actor")
                .build());
        assertThat(ingestResponse.getAccepted()).isTrue();

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            AuditQueryResponse response = client.query(null, "client-round-trip", null, null, 0, 10);
            assertThat(response.getRecordsList()).hasSize(1);
            assertThat(response.getRecordsList().get(0).getActorId()).isEqualTo("client-actor");
        });
    }
}
