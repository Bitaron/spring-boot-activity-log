package io.github.bitaron.auditlog.server;

import io.github.bitaron.auditlog.contract.AuditLogRecorder;
import io.github.bitaron.auditlog.contract.AuditTenantResolver;
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
 * <p>
 * <b>Tenant is authenticated, not caller-suppliable (WP16):</b> the event's {@code tenant_id} comes
 * from {@link AuditTenantResolver} - i.e. whichever tenant {@link ApiKeyAuthFilter} authenticated
 * the request as via the presented {@code X-API-Key} - never from the request body. A body that
 * names a different {@code tenant_id} than the one authenticated is rejected outright, rather than
 * silently overridden, so a caller notices the mismatch instead of an event quietly landing under
 * an unexpected tenant.
 */
@RestController
@RequestMapping("/audit-log/events")
public class AuditIngestController {

    private final AuditLogRecorder auditLogRecorder;
    private final AuditTenantResolver auditTenantResolver;

    public AuditIngestController(AuditLogRecorder auditLogRecorder, AuditTenantResolver auditTenantResolver) {
        this.auditLogRecorder = auditLogRecorder;
        this.auditTenantResolver = auditTenantResolver;
    }

    /**
     * Returns {@code 202 Accepted} once the event has been handed to {@link AuditLogRecorder} -
     * the write itself still follows the configured delivery mode ({@code audit.log.mode}), so
     * {@code 202} means "accepted for processing", not "durably committed".
     *
     * @throws IllegalArgumentException (400, via {@link AuditServerExceptionHandler}) if the
     * request body's {@code tenant_id} is set and doesn't match the authenticated tenant
     * @throws IllegalStateException (400, via {@link AuditServerExceptionHandler}) if no tenant
     * could be authenticated for this request at all - shouldn't happen in practice since
     * {@link ApiKeyAuthFilter} already rejects any request that doesn't resolve one, but guards
     * against a misconfigured {@link AuditTenantResolver} override silently tagging events with no
     * tenant instead of failing loudly
     */
    @PostMapping
    public ResponseEntity<AuditEventResponse> ingest(
            @RequestBody io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest request) {
        String authenticatedTenantId = auditTenantResolver.resolveTenantId();
        if (authenticatedTenantId == null) {
            throw new IllegalStateException("No tenant could be authenticated for this request - refusing to "
                    + "record an event with no tenant");
        }
        String requestedTenantId = request.getTenantId().isBlank() ? null : request.getTenantId();
        if (requestedTenantId != null && !requestedTenantId.equals(authenticatedTenantId)) {
            throw new IllegalArgumentException("tenant_id \"" + requestedTenantId + "\" does not match the "
                    + "tenant authenticated by the supplied API key (\"" + authenticatedTenantId + "\")");
        }
        auditLogRecorder.record(ProtoMapper.toEventRequest(request, authenticatedTenantId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ProtoMapper.toEventResponse(true));
    }
}
