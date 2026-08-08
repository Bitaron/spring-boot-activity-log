package io.github.bitaron.auditlog.grpc;

import io.github.bitaron.auditlog.contract.AuditLogRecorder;
import io.github.bitaron.auditlog.contract.AuditTenantResolver;
import io.github.bitaron.auditlog.query.AuditCursor;
import io.github.bitaron.auditlog.query.AuditLogQueryService;
import io.github.bitaron.auditlog.query.AuditQuery;
import io.github.bitaron.auditlog.query.AuditRecord;
import io.github.bitaron.auditlog.server.proto.v1.AuditCursorQueryRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditCursorQueryResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditLogServiceGrpc;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditRecordProto;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * gRPC equivalent of {@code AuditIngestController}/{@code AuditQueryController} - implements the
 * {@code AuditLogService} RPCs defined in {@code audit_event.proto} on top of the same
 * {@link AuditLogRecorder}/{@link AuditLogQueryService} beans the core starter (and the REST
 * server module, when that's what's enabled instead) already use. All dispatch/persistence/query
 * logic lives there, not here - this class is a thin gRPC-shaped wrapper, exactly as thin as its
 * REST counterparts.
 * <p>
 * <b>Tenant is authenticated, not caller-suppliable</b> - see {@code ingest}'s javadoc, mirroring
 * {@code AuditIngestController}'s identical rule on the REST side.
 */
@Slf4j
public class AuditLogGrpcService extends AuditLogServiceGrpc.AuditLogServiceImplBase {

    private final AuditLogRecorder auditLogRecorder;
    private final AuditLogQueryService auditLogQueryService;
    private final AuditTenantResolver auditTenantResolver;

    public AuditLogGrpcService(AuditLogRecorder auditLogRecorder, AuditLogQueryService auditLogQueryService,
                                AuditTenantResolver auditTenantResolver) {
        this.auditLogRecorder = auditLogRecorder;
        this.auditLogQueryService = auditLogQueryService;
        this.auditTenantResolver = auditTenantResolver;
    }

    /**
     * The persisted event's tenant is always the one {@link ApiKeyGrpcServerInterceptor}
     * authenticated the call as - a request naming a different {@code tenant_id} is rejected
     * ({@code INVALID_ARGUMENT}), never silently overridden. Same rule, same reasoning, as
     * {@code AuditIngestController#ingest} on the REST side.
     */
    @Override
    public void ingest(io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest request,
                        StreamObserver<AuditEventResponse> responseObserver) {
        try {
            String authenticatedTenantId = requireAuthenticatedTenant();
            String requestedTenantId = request.getTenantId().isBlank() ? null : request.getTenantId();
            if (requestedTenantId != null && !requestedTenantId.equals(authenticatedTenantId)) {
                throw new IllegalArgumentException("tenant_id \"" + requestedTenantId + "\" does not match the "
                        + "tenant authenticated by the supplied x-api-key (\"" + authenticatedTenantId + "\")");
            }
            auditLogRecorder.record(GrpcProtoMapper.toEventRequest(request, authenticatedTenantId));
            responseObserver.onNext(GrpcProtoMapper.toEventResponse(true));
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            handleError(e, responseObserver);
        }
    }

    @Override
    public void query(AuditQueryRequest request, StreamObserver<AuditQueryResponse> responseObserver) {
        try {
            AuditQuery query = new AuditQuery(
                    emptyToNull(request.getActorId()),
                    emptyToNull(request.getAuditType()),
                    parseOrNull(request.getCreatedAtFrom()),
                    parseOrNull(request.getCreatedAtTo()));
            Page<AuditRecord> result = auditLogQueryService.find(query, PageRequest.of(request.getPage(), request.getSize()));
            List<AuditRecordProto> records = result.getContent().stream().map(GrpcProtoMapper::toRecordProto).toList();
            responseObserver.onNext(GrpcProtoMapper.toQueryResponse(
                    records, result.getTotalElements(), request.getPage(), request.getSize()));
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            handleError(e, responseObserver);
        }
    }

    @Override
    public void queryAfter(AuditCursorQueryRequest request, StreamObserver<AuditCursorQueryResponse> responseObserver) {
        try {
            boolean hasCursorCreatedAt = !request.getCursorCreatedAt().isEmpty();
            if (hasCursorCreatedAt != (request.getCursorId() != 0)) {
                throw new IllegalArgumentException(
                        "cursor_created_at and cursor_id must both be supplied together, or neither, for the first page");
            }
            AuditQuery query = new AuditQuery(
                    emptyToNull(request.getActorId()),
                    emptyToNull(request.getAuditType()),
                    parseOrNull(request.getCreatedAtFrom()),
                    parseOrNull(request.getCreatedAtTo()));
            AuditCursor cursor = hasCursorCreatedAt
                    ? new AuditCursor(LocalDateTime.parse(request.getCursorCreatedAt()), request.getCursorId())
                    : null;
            List<AuditRecordProto> records = auditLogQueryService.findAfter(query, cursor, request.getLimit()).stream()
                    .map(GrpcProtoMapper::toRecordProto).toList();
            responseObserver.onNext(GrpcProtoMapper.toCursorQueryResponse(records));
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            handleError(e, responseObserver);
        }
    }

    /**
     * @throws IllegalStateException if no tenant could be authenticated for this call at all -
     * shouldn't happen in practice since {@link ApiKeyGrpcServerInterceptor} already rejects any
     * call that doesn't resolve one, but guards against a misconfigured {@link AuditTenantResolver}
     * override silently proceeding with no tenant instead of failing loudly
     */
    private String requireAuthenticatedTenant() {
        String tenantId = auditTenantResolver.resolveTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant could be authenticated for this call - refusing to "
                    + "proceed with no tenant");
        }
        return tenantId;
    }

    /**
     * Translates the same caller-error exceptions {@code AuditServerExceptionHandler} maps to
     * {@code 400} on the REST side into {@link Status#INVALID_ARGUMENT} here - anything else is
     * logged and reported as {@link Status#INTERNAL} with no internal detail exposed, rather than
     * letting an arbitrary exception message leak to the caller.
     */
    private void handleError(RuntimeException e, StreamObserver<?> responseObserver) {
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).withCause(e).asRuntimeException());
        } else {
            log.error("Unexpected error handling a gRPC audit-log request", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static LocalDateTime parseOrNull(String value) {
        return value == null || value.isEmpty() ? null : LocalDateTime.parse(value);
    }
}
