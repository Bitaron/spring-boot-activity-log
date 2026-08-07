package io.github.bitaron.auditlog.grpc;

import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditLogServiceGrpc;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP16-equivalent acceptance tests for real per-tenant gRPC authentication: two tenants, each with
 * its own {@code audit.log.grpc.api-keys.<tenantId>} secret. Kept as a separate test class from
 * {@link AuditLogGrpcServerIntegrationTest} since that class deliberately configures only one
 * tenant.
 * <p>
 * The core guarantee under test: which tenant a call acts as is determined entirely by which
 * {@code x-api-key} metadata entry it presents - never by anything the caller puts in the request
 * message - so a caller holding tenant-a's key can never read or write tenant-b's data, and vice
 * versa.
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
                "audit.log.grpc.api-keys.tenant-a=key-a",
                "audit.log.grpc.api-keys.tenant-b=key-b"
        })
class AuditLogGrpcServerMultiTenancyIntegrationTest {

    @Autowired
    private AuditLogGrpcServer grpcServer;

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

    /**
     * The cross-tenant-isolation guarantee, over a real gRPC channel: events ingested under
     * tenant-a's and tenant-b's own keys (neither request ever names a tenant - it's authenticated
     * from the key alone) stay isolated when queried back - a caller holding only tenant-a's key
     * can never see tenant-b's row, no matter how it queries, and vice versa.
     */
    @Test
    void eachTenantsKeyOnlyEverSeesItsOwnData() {
        ingest("key-a", "tenant-scoped-event-grpc");
        ingest("key-b", "tenant-scoped-event-grpc");

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            AuditQueryResponse responseA = stubWithApiKey("key-a").query(AuditQueryRequest.newBuilder()
                    .setAuditType("tenant-scoped-event-grpc")
                    .setSize(10)
                    .build());
            assertThat(responseA.getTotalElements()).isEqualTo(1);
            assertThat(responseA.getRecords(0).getTenantId()).isEqualTo("tenant-a");
        });

        AuditQueryResponse responseB = stubWithApiKey("key-b").query(AuditQueryRequest.newBuilder()
                .setAuditType("tenant-scoped-event-grpc")
                .setSize(10)
                .build());
        assertThat(responseB.getTotalElements()).isEqualTo(1);
        assertThat(responseB.getRecords(0).getTenantId()).isEqualTo("tenant-b");
    }

    /** A key that doesn't match any configured tenant is rejected, same as no key at all. */
    @Test
    void queryWithAnUnrecognizedKeyIsRejected() {
        AuditLogServiceGrpc.AuditLogServiceBlockingStub stub = stubWithApiKey("not-a-configured-key");
        AuditQueryRequest request = AuditQueryRequest.newBuilder().build();

        assertThatThrownBy(() -> stub.query(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    /** tenant-a's key cannot be used to write data that claims to be tenant-b's. */
    @Test
    void tenantAsKeyCannotIngestAsTenantB() {
        AuditLogServiceGrpc.AuditLogServiceBlockingStub stub = stubWithApiKey("key-a");
        AuditEventRequest request = AuditEventRequest.newBuilder()
                .setAuditType("cross-tenant-attempt-grpc")
                .setTenantId("tenant-b")
                .build();

        assertThatThrownBy(() -> stub.ingest(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private void ingest(String apiKey, String auditType) {
        stubWithApiKey(apiKey).ingest(AuditEventRequest.newBuilder()
                .setAuditType(auditType)
                .build()); // no tenant_id on the wire - authenticated from apiKey alone, see class javadoc
    }

    private AuditLogServiceGrpc.AuditLogServiceBlockingStub stubWithApiKey(String apiKey) {
        Metadata metadata = new Metadata();
        metadata.put(ApiKeyGrpcServerInterceptor.API_KEY_METADATA_KEY, apiKey);
        return AuditLogServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }
}
