package io.github.bitaron.auditlog.server;

import io.github.bitaron.auditlog.entity.AuditOutcome;
import io.github.bitaron.auditlog.model.AuditEventRequest;
import io.github.bitaron.auditlog.query.AuditRecord;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditOutcomeProto;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditRecordProto;

import java.util.List;
import java.util.function.Consumer;

/**
 * Maps between this module's Protobuf wire types ({@code io.github.bitaron.auditlog.server.proto.v1})
 * and the core starter's Java model types. Kept as a standalone mapper rather than spread across
 * the controllers, so the wire<->domain translation is in exactly one place.
 * <p>
 * {@code io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest} (the wire message) and
 * {@code io.github.bitaron.auditlog.model.AuditEventRequest} (the domain type) share a simple
 * name, hence the fully-qualified reference on {@link #toEventRequest} rather than importing both.
 */
final class ProtoMapper {

    private ProtoMapper() {
    }

    static AuditEventRequest toEventRequest(io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest proto) {
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
                emptyToNull(proto.getTraceId()));
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
