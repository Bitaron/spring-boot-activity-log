package io.github.bitaron.auditlog.client;

import io.github.bitaron.auditlog.server.proto.v1.AuditCursorQueryRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditCursorQueryResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
     * WP17 acceptance: {@link AuditLogHttpClient#query(AuditQueryRequest)} combines multiple
     * filters (actor + type + a created-at range) in one call, matching only the row that
     * satisfies all of them - not just whichever filter is exercised in the round-trip test above.
     */
    @Test
    void queryWithMultipleFiltersCombinedMatchesOnlyTheRowSatisfyingAll() {
        AuditLogHttpClient client = new AuditLogHttpClient("http://localhost:" + port, "client-test-key");
        // AuditLogWriter always stamps createdAt via LocalDateTime.now(ZoneOffset.UTC) - the
        // range filter bounds must use the same clock, not the system-default zone, or this
        // window silently excludes the row on any machine whose default zone isn't UTC.
        LocalDateTime from = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1);

        client.ingest(AuditEventRequest.newBuilder()
                .setAuditType("multi-filter-event")
                .setActorId("multi-filter-actor")
                .build());
        client.ingest(AuditEventRequest.newBuilder()
                .setAuditType("multi-filter-event")
                .setActorId("some-other-actor")
                .build());

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            AuditQueryResponse response = client.query(AuditQueryRequest.newBuilder()
                    .setActorId("multi-filter-actor")
                    .setAuditType("multi-filter-event")
                    .setCreatedAtFrom(from.toString())
                    .setCreatedAtTo(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1).toString())
                    .setPage(0)
                    .setSize(10)
                    .build());
            assertThat(response.getRecordsList()).hasSize(1);
            assertThat(response.getRecords(0).getActorId()).isEqualTo("multi-filter-actor");
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

    /**
     * WP17 acceptance: {@link AuditLogHttpClient#queryAfter} walks a 3-row result set two pages
     * (2 then 1) with no overlap, the same keyset-pagination guarantee
     * {@code AuditLogQueryService.findAfter} gives in-process, now reachable remotely.
     */
    @Test
    void queryAfterWalksPagesViaKeysetPagination() {
        AuditLogHttpClient client = new AuditLogHttpClient("http://localhost:" + port, "client-test-key");

        for (String actorId : new String[]{"cursor-actor-1", "cursor-actor-2", "cursor-actor-3"}) {
            client.ingest(AuditEventRequest.newBuilder()
                    .setAuditType("client-cursor-pagination")
                    .setActorId(actorId)
                    .build());
        }

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            AuditCursorQueryResponse firstPage = client.queryAfter(AuditCursorQueryRequest.newBuilder()
                    .setAuditType("client-cursor-pagination")
                    .setLimit(2)
                    .build());
            assertThat(firstPage.getRecordsList()).hasSize(2);

            var last = firstPage.getRecords(1);
            AuditCursorQueryResponse secondPage = client.queryAfter(AuditCursorQueryRequest.newBuilder()
                    .setAuditType("client-cursor-pagination")
                    .setCursorCreatedAt(last.getCreatedAt())
                    .setCursorId(last.getId())
                    .setLimit(2)
                    .build());
            assertThat(secondPage.getRecordsList()).hasSize(1);
        });
    }
}
