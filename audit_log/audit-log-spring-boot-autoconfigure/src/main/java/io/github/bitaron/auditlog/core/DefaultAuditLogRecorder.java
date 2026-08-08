package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.ActorSource;
import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.annotation.AuditDeliveryMode;
import io.github.bitaron.auditlog.contract.AuditLogRecorder;
import io.github.bitaron.auditlog.model.AuditContext;
import io.github.bitaron.auditlog.model.AuditEventRequest;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default {@link AuditLogRecorder}: builds an {@link AuditContext} directly from the request
 * (there is no join point for an {@link io.github.bitaron.auditlog.core.AuditContextResolver} to
 * inspect) and delegates to {@link AuditLogger} - the same dispatch {@link AuditLogAspect} uses,
 * not a parallel reimplementation of it.
 * <p>
 * {@link AuditLogWriter}/{@link AuditLogger} are written against the {@link Audit} annotation
 * type, since that's what {@link AuditLogAspect} always had on hand. Rather than duplicating their
 * dispatch/persistence logic for a caller that has no real annotation instance, this class
 * synthesizes one from {@code request}'s fields via Spring's
 * {@link AnnotationUtils#synthesizeAnnotation} - a dynamic proxy backing the {@link Audit}
 * interface, indistinguishable to {@code AuditLogWriter}/{@code AuditLogger} from one the compiler
 * generated on a real annotated method.
 */
public class DefaultAuditLogRecorder implements AuditLogRecorder {

    private final AuditLogger auditLogger;

    public DefaultAuditLogRecorder(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @Override
    public void record(AuditEventRequest request) {
        Audit audit = synthesizeAudit(request);
        AuditContext context = new AuditContext(
                request.actorId(),
                request.actorName(),
                request.clientLocation(),
                request.clientIp(),
                request.userAgent(),
                request.args(),
                request.exceptionThrown() ? null : request.result(),
                request.exceptionThrown() ? request.exception() : null,
                request.exceptionThrown(),
                request.durationMillis(),
                request.traceId(),
                request.tenantId());
        auditLogger.log(audit, context);
    }

    /**
     * {@code actorSource}/{@code actorExpression} are left at their annotation defaults - they're
     * only meaningful relative to a join point, and {@code request} already supplies the resolved
     * actor directly (see {@link AuditEventRequest}'s javadoc). {@code mode} is left at
     * {@link AuditDeliveryMode#INHERIT}: a programmatic caller can't express a per-call delivery
     * override through this API today, so it follows whatever {@code audit.log.mode} is configured.
     */
    private Audit synthesizeAudit(AuditEventRequest request) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("auditType", request.auditType());
        attributes.put("actionName", request.actionName());
        attributes.put("actionType", request.actionType());
        attributes.put("groupName", request.groupName());
        attributes.put("templates", request.templates().toArray(new String[0]));
        attributes.put("actorSource", ActorSource.CONTEXT);
        attributes.put("actorExpression", "");
        attributes.put("mode", AuditDeliveryMode.INHERIT);
        return AnnotationUtils.synthesizeAnnotation(attributes, Audit.class, null);
    }
}
