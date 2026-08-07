package io.github.bitaron.auditlog.grpc;

import io.github.bitaron.auditlog.entity.AuditOutcome;
import io.github.bitaron.auditlog.model.AuditEventRequest;
import io.github.bitaron.auditlog.query.AuditRecord;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditOutcomeProto;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditRecordProto;
import io.github.bitaron.auditlog.server.proto.v1.AuditCursorQueryResponse;

import java.util.List;
import java.util.function.Consumer;

/**
 * Maps between the shared Protobuf wire types ({@code io.github.bitaron.auditlog.server.proto.v1})
 * and the core starter's Java model types - the wire<->domain translation this module needs.
 * <p>
 * <b>Deliberately duplicated from {@code audit-log-spring-boot-server}'s package-private
 * {@code ProtoMapper}, not shared</b>: the mapping logic is identical (same generated message
 * types, same domain types), but the REST module's {@code ProtoMapper} lives in a package-private
 * class in a module this one must not depend on - depending on {@code audit-log-spring-boot-server}
 * just to reuse ~80 lines of mapping would force Spring MVC/Servlet dependencies onto every
 * gRPC-only deployment, exactly the coupling {@link AuditLogGrpcServerAutoConfiguration}'s javadoc
 * explains these two modules must not have. If a third wire protocol is ever added, extracting a
 * shared mapping module (with no Servlet/gRPC dependency of its own) becomes worth it; for two,
 * this duplication is the smaller cost.
 */
final class GrpcProtoMapper {

    private GrpcProtoMapper() {
    }

    /**
     * @param tenantId the authenticated tenant - not read from {@code proto.getTenantId()} here;
     * {@link AuditLogGrpcService} already validated the wire value (if any) against this before
     * calling in, so this parameter is the single source of truth for the resulting domain
     * object's tenant
     */
    static AuditEventRequest toEventRequest(io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest proto,
                                              String tenantId) {
        return new AuditEventRequest(
                proto.getAuditType(),
                proto.getActionName(),
                proto.getActionType(),
                proto.getGroupName(),
                proto.getTemplatesList(),
                emptyToNull(proto.getActorId()),
                emptyToNull(proto.getActorName()),
                emptyToNull(proto.getClientIp()),
                emptyToNull(proto.getClientLocation()),
                emptyToNull(proto.getUserAgent()),
                emptyToNull(proto.getArgsJson()),
                emptyToNull(proto.getResultJson()),
                emptyToNull(proto.getExceptionJson()),
                proto.getExceptionThrown(),
                proto.getDurationMillis(),
                emptyToNull(proto.getTraceId()),
                tenantId);
    }

    static AuditEventResponse toEventResponse(boolean accepted) {
        return AuditEventResponse.newBuilder().setAccepted(accepted).build();
    }

    static AuditRecordProto toRecordProto(AuditRecord record) {
        AuditRecordProto.Builder builder = AuditRecordProto.newBuilder()
                .setId(nullToZero(record.id()))
                .setOutcome(toOutcomeProto(record.outcome()))
                .setDurationMs(nullToZero(record.durationMs()))
                .setGroupId(nullToZero(record.groupId()));
        setIfNotNull(record.auditType(), builder::setAuditType);
        setIfNotNull(record.actorId(), builder::setActorId);
        setIfNotNull(record.actorName(), builder::setActorName);
        setIfNotNull(record.clientIp(), builder::setClientIp);
        setIfNotNull(record.clientLocation(), builder::setClientLocation);
        setIfNotNull(record.userAgent(), builder::setUserAgent);
        setIfNotNull(record.actionType(), builder::setActionType);
        setIfNotNull(record.actionName(), builder::setActionName);
        setIfNotNull(record.traceId(), builder::setTraceId);
        setIfNotNull(record.data(), builder::setData);
        setIfNotNull(record.tenantId(), builder::setTenantId);
        if (record.createdAt() != null) {
            builder.setCreatedAt(record.createdAt().toString());
        }
        return builder.build();
    }

    static AuditQueryResponse toQueryResponse(List<AuditRecordProto> records, long totalElements, int page, int size) {
        return AuditQueryResponse.newBuilder()
                .addAllRecords(records)
                .setTotalElements(totalElements)
                .setPage(page)
                .setSize(size)
                .build();
    }

    static AuditCursorQueryResponse toCursorQueryResponse(List<AuditRecordProto> records) {
        return AuditCursorQueryResponse.newBuilder().addAllRecords(records).build();
    }

    private static AuditOutcomeProto toOutcomeProto(AuditOutcome outcome) {
        if (outcome == null) {
            return AuditOutcomeProto.AUDIT_OUTCOME_UNSPECIFIED;
        }
        return switch (outcome) {
            case SUCCESS -> AuditOutcomeProto.AUDIT_OUTCOME_SUCCESS;
            case FAILURE -> AuditOutcomeProto.AUDIT_OUTCOME_FAILURE;
        };
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static void setIfNotNull(String value, Consumer<String> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
