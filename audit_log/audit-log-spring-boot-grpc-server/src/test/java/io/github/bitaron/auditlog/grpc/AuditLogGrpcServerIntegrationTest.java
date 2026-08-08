package io.github.bitaron.auditlog.grpc;

import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.server.proto.v1.AuditCursorQueryRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditCursorQueryResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditLogServiceGrpc;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditRecordProto;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import jakarta.persistence.EntityManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP18 acceptance test - the real-transport gRPC equivalent of
 * {@code AuditLogServerIntegrationTest} on the REST side: a real {@link ManagedChannel} against
 * the actual bound port (see {@link AuditLogGrpcServer#getPort()}), a real
 * {@link AuditLogServiceGrpc.AuditLogServiceBlockingStub}, and real {@link ApiKeyGrpcServerInterceptor}
 * authentication via the {@code x-api-key} metadata entry. Single-tenant config here (one key); see
 * {@link AuditLogGrpcServerMultiTenancyIntegrationTest} for the cross-tenant-isolation guarantee
 * with more than one.
 */
@SpringBootTest(
        classes = GrpcTestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.generate-unique-name=true",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "audit.log.grpc.enabled=true",
                "audit.log.multi-tenancy.enabled=true",
                "audit.log.grpc.port=0",
                "audit.log.grpc.api-keys.tenant-a=test-api-key"
        })
class AuditLogGrpcServerIntegrationTest {

    private static final String API_KEY = "test-api-key";

    @Autowired
    private AuditLogGrpcServer grpcServer;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ManagedChannel channel;

    @BeforeEach
    void openChannel() {
        channel = ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort())
                .usePlaintext()
                .build();
    }

    @AfterEach
    void closeChannel() throws InterruptedException {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void ingestWithoutApiKeyIsRejected() {
        AuditLogServiceGrpc.AuditLogServiceBlockingStub stub = AuditLogServiceGrpc.newBlockingStub(channel);
        AuditEventRequest request = AuditEventRequest.newBuilder().setAuditType("remote-event").build();

        assertThatThrownBy(() -> stub.ingest(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void ingestWithAnUnknownApiKeyIsRejected() {
        AuditLogServiceGrpc.AuditLogServiceBlockingStub stub = stubWithApiKey("not-a-configured-key");
        AuditEventRequest request = AuditEventRequest.newBuilder().setAuditType("remote-event").build();

        assertThatThrownBy(() -> stub.ingest(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void ingestPersistsAndIsVisibleThroughQuery() {
        AuditLogServiceGrpc.AuditLogServiceBlockingStub stub = stubWithApiKey(API_KEY);

        stub.ingest(AuditEventRequest.newBuilder()
                .setAuditType("remote-event-grpc")
                .setActorId("remote-actor")
                .build());

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(findByAuditType("remote-event-grpc")).hasSize(1));

        AuditQueryResponse response = stub.query(AuditQueryRequest.newBuilder()
                .setAuditType("remote-event-grpc")
                .setPage(0)
                .setSize(10)
                .build());

        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getRecordsList()).hasSize(1);
        AuditRecordProto record = response.getRecords(0);
        assertThat(record.getActorId()).isEqualTo("remote-actor");
        // (mirrors the REST-side WP16 rule) tenant_id was never set on the request - it's
        // authenticated from the API key, not caller-suppliable.
        assertThat(record.getTenantId()).isEqualTo("tenant-a");
    }

    /** WP16-equivalent acceptance: the persisted tenant is the one the API key authenticated, not
     * whatever (if anything) the request's {@code tenant_id} says. */
    @Test
    void ingestPersistsTheAuthenticatedTenantRegardlessOfWireTenantId() {
        AuditLogServiceGrpc.AuditLogServiceBlockingStub stub = stubWithApiKey(API_KEY);

        stub.ingest(AuditEventRequest.newBuilder()
                .setAuditType("remote-event-tenant")
                .setActorId("tenant-actor")
                .build()); // no tenant_id set on the wire at all

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(findByAuditType("remote-event-tenant"))
                        .extracting(AuditLog::getTenantId).containsExactly("tenant-a"));
    }

    /** A request naming a *different* tenant than the one authenticated is rejected outright, not
     * silently overridden or accepted. */
    @Test
    void ingestWithMismatchedTenantIdIsRejected() {
        AuditLogServiceGrpc.AuditLogServiceBlockingStub stub = stubWithApiKey(API_KEY);

        AuditEventRequest request = AuditEventRequest.newBuilder()
                .setAuditType("remote-event-mismatch")
                .setTenantId("some-other-tenant")
                .build();

        assertThatThrownBy(() -> stub.ingest(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    /**
     * WP17-equivalent acceptance: {@code QueryAfter} keyset-paginates the same way
     * {@link io.github.bitaron.auditlog.query.AuditLogQueryService#findAfter} does in-process -
     * walking two pages of 2 with a 3-row result set returns 2 then 1, with no overlap.
     */
    @Test
    void queryAfterWalksPagesViaKeysetPagination() {
        AuditLogServiceGrpc.AuditLogServiceBlockingStub stub = stubWithApiKey(API_KEY);
        ingest(stub, "cursor-pagination-event-grpc", "actor-1");
        ingest(stub, "cursor-pagination-event-grpc", "actor-2");
        ingest(stub, "cursor-pagination-event-grpc", "actor-3");

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(findByAuditType("cursor-pagination-event-grpc")).hasSize(3));

        AuditCursorQueryResponse firstPage = stub.queryAfter(AuditCursorQueryRequest.newBuilder()
                .setAuditType("cursor-pagination-event-grpc")
                .setLimit(2)
                .build());
        assertThat(firstPage.getRecordsList()).hasSize(2);

        AuditRecordProto last = firstPage.getRecords(1);
        AuditCursorQueryResponse secondPage = stub.queryAfter(AuditCursorQueryRequest.newBuilder()
                .setAuditType("cursor-pagination-event-grpc")
                .setCursorCreatedAt(last.getCreatedAt())
                .setCursorId(last.getId())
                .setLimit(2)
                .build());
        assertThat(secondPage.getRecordsList()).hasSize(1);
    }

    /** A cursor with only one of the two required fields is rejected, rather than silently treated
     * as "first page" or "no lower bound". */
    @Test
    void queryAfterRejectsAHalfSuppliedCursor() {
        AuditLogServiceGrpc.AuditLogServiceBlockingStub stub = stubWithApiKey(API_KEY);
        AuditCursorQueryRequest request = AuditCursorQueryRequest.newBuilder()
                .setCursorId(1)
                .build();

        assertThatThrownBy(() -> stub.queryAfter(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private AuditLogServiceGrpc.AuditLogServiceBlockingStub stubWithApiKey(String apiKey) {
        Metadata metadata = new Metadata();
        metadata.put(ApiKeyGrpcServerInterceptor.API_KEY_METADATA_KEY, apiKey);
        return AuditLogServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private void ingest(AuditLogServiceGrpc.AuditLogServiceBlockingStub stub, String auditType, String actorId) {
        stub.ingest(AuditEventRequest.newBuilder()
                .setAuditType(auditType)
                .setActorId(actorId)
                .build());
    }

    private List<AuditLog> findByAuditType(String auditType) {
        return new TransactionTemplate(transactionManager).execute(status ->
                entityManager.createQuery("select a from AuditLog a where a.auditType = :t", AuditLog.class)
                        .setParameter("t", auditType)
                        .getResultList());
    }
}
