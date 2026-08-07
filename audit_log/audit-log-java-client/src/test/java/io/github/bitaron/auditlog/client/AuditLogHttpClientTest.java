package io.github.bitaron.auditlog.client;

import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * WP17 acceptance: the {@code (RestClient.Builder, baseUrl, apiKey)} constructor actually
     * routes requests through the supplied builder's {@link ClientHttpRequestFactory} - the seam
     * autoconfiguration (and any caller wanting custom timeouts/interceptors) needs.
     */
    @Test
    void constructorWithCustomRestClientBuilderUsesTheSuppliedRequestFactory() {
        AtomicInteger requestCount = new AtomicInteger();
        ClientHttpRequestFactory delegate = new SimpleClientHttpRequestFactory();
        ClientHttpRequestFactory countingFactory = (uri, httpMethod) -> {
            requestCount.incrementAndGet();
            return delegate.createRequest(uri, httpMethod);
        };

        AuditLogHttpClient client = new AuditLogHttpClient(
                RestClient.builder().requestFactory(countingFactory),
                "http://localhost:" + port, "client-test-key");

        AuditEventResponse response = client.ingest(AuditEventRequest.newBuilder()
                .setAuditType("ctor-test")
                .setActorId("ctor-actor")
                .build());

        assertThat(response.getAccepted()).isTrue();
        assertThat(requestCount.get()).isEqualTo(1);
    }
}
