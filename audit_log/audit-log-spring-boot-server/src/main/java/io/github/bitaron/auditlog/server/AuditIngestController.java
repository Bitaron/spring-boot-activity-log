package io.github.bitaron.auditlog.server;

import io.github.bitaron.auditlog.contract.AuditLogRecorder;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /audit-log/events}: records one fully-described audit event submitted by a caller
 * with no {@code @Audit}-annotated method invocation for AOP to intercept. A thin HTTP wrapper
 * around {@link AuditLogRecorder} - all dispatch/persistence logic lives there, not here.
 * <p>
 * Accepts and returns {@code application/x-protobuf} or, for debugging/curl convenience,
 * {@code application/json} - both handled by the {@code ProtobufHttpMessageConverter} bean this
 * module registers (Spring's built-in one, not custom (de)serialization code).
 */
@RestController
@RequestMapping("/audit-log/events")
public class AuditIngestController {

    private final AuditLogRecorder auditLogRecorder;
    private final boolean multiTenancyRequired;

    public AuditIngestController(AuditLogRecorder auditLogRecorder, AuditLogServerProperties serverProperties) {
        this.auditLogRecorder = auditLogRecorder;
        this.multiTenancyRequired = serverProperties.getMultiTenancy().isRequired();
    }

    /**
     * Returns {@code 202 Accepted} once the event has been handed to {@link AuditLogRecorder} -
     * the write itself still follows the configured delivery mode ({@code audit.log.mode}), so
     * {@code 202} means "accepted for processing", not "durably committed".
     *
     * @throws IllegalArgumentException (400, via {@link AuditServerExceptionHandler}) if
     * {@code audit.log.server.multi-tenancy.required=true} and {@code tenant_id} is blank
     */
    @PostMapping
    public ResponseEntity<AuditEventResponse> ingest(
            @RequestBody io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest request) {
        if (multiTenancyRequired && request.getTenantId().isBlank()) {
            throw new IllegalArgumentException(
                    "tenant_id is required when audit.log.server.multi-tenancy.required=true");
        }
        auditLogRecorder.record(ProtoMapper.toEventRequest(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ProtoMapper.toEventResponse(true));
    }
}
