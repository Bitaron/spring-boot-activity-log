package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.contract.AuditLogArgumentSerializer;
import io.github.bitaron.auditlog.contract.AuditLogTemplateResolver;
import io.github.bitaron.auditlog.entity.AuditGroup;
import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.entity.AuditLogMessage;
import io.github.bitaron.auditlog.entity.AuditOutcome;
import io.github.bitaron.auditlog.entity.AuditTemplate;
import io.github.bitaron.auditlog.model.AuditContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Performs the actual persistence of one {@code @Audit} invocation's audit record.
 * <p>
 * Exposes two entry points with different transaction semantics, corresponding to
 * {@link io.github.bitaron.auditlog.properties.AuditLogProperties.DeliveryMode}:
 * <ul>
 *   <li>{@link #persistRequiresNew} - its own transaction ({@link Propagation#REQUIRES_NEW}),
 *   for {@code ASYNC} delivery. Independent of the caller's transaction by design: it's invoked
 *   on a different thread after that transaction has already committed (see
 *   {@link AuditLogger}), so there is no caller transaction left to join.</li>
 *   <li>{@link #persistShared} - joins the caller's transaction ({@link Propagation#REQUIRED}),
 *   for {@code SYNC} delivery. Commits and rolls back atomically with the business operation it
 *   describes - the strongest delivery guarantee this library offers.</li>
 * </ul>
 * Kept as a separate bean from {@link AuditLogger} - rather than a method on it - so that
 * Spring's {@code @Transactional} proxy is actually invoked. Calling either method via
 * {@code this.persistX(...)} from within the same class would silently bypass the proxy and the
 * transaction boundary; self-invocation is a well-known Spring AOP limitation.
 */
@Slf4j
public class AuditLogWriter {

    private final EntityManager entityManager;
    private final AuditLogTemplateResolver auditLogTemplateResolver;
    private final AuditLogArgumentSerializer auditLogArgumentSerializer;

    public AuditLogWriter(EntityManager entityManager,
                           AuditLogTemplateResolver auditLogTemplateResolver,
                           AuditLogArgumentSerializer auditLogArgumentSerializer) {
        this.entityManager = entityManager;
        this.auditLogTemplateResolver = auditLogTemplateResolver;
        this.auditLogArgumentSerializer = auditLogArgumentSerializer;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistRequiresNew(Audit audit, AuditContext auditContext) {
        doPersist(audit, auditContext);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void persistShared(Audit audit, AuditContext auditContext) {
        doPersist(audit, auditContext);
    }

    private void doPersist(Audit audit, AuditContext auditContext) {
        List<String> templateNames = Arrays.stream(audit.templates())
                .distinct()
                .toList();

        Map<String, AuditTemplate> templatesByName = templateNames.isEmpty()
                ? Map.of()
                : findTemplatesByName(templateNames).stream()
                        .collect(Collectors.toMap(AuditTemplate::getName, Function.identity(), (a, b) -> a));

        List<AuditLogMessage> messages = new ArrayList<>();
        for (String templateName : templateNames) {
            AuditTemplate auditTemplate = templatesByName.get(templateName);
            if (auditTemplate == null) {
                log.warn("@Audit references template \"{}\" but no matching audit_template row exists; skipping it",
                        templateName);
                continue;
            }
            String message = auditLogTemplateResolver.resolveTemplate(
                    auditTemplate.getName(), auditTemplate.getTemplate(), auditContext);
            messages.add(buildMessage(auditTemplate.getId(), message));
        }

        // Templates were named but none of them resolved: nothing meaningful to record.
        if (!templateNames.isEmpty() && messages.isEmpty()) {
            return;
        }

        AuditLog auditLog = buildAuditLog(audit, auditContext);
        entityManager.persist(auditLog);
        for (AuditLogMessage message : messages) {
            message.setAuditLogId(auditLog.getId());
            entityManager.persist(message);
        }
    }

    private AuditLog buildAuditLog(Audit audit, AuditContext auditContext) {
        AuditLog auditLog = new AuditLog();
        auditLog.setAuditType(audit.auditType());
        auditLog.setActionName(audit.actionName());
        auditLog.setActionType(audit.actionType());
        auditLog.setActorId(auditContext.actorId());
        auditLog.setActorName(auditContext.actorName());
        auditLog.setClientIp(auditContext.clientIp());
        auditLog.setClientLocation(auditContext.clientLocation());
        auditLog.setUserAgent(auditContext.userAgent());
        auditLog.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        auditLog.setOutcome(auditContext.exceptionThrown() ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS);
        auditLog.setDurationMs(auditContext.durationMillis());
        auditLog.setTraceId(auditContext.traceId());
        auditLog.setData(serializeData(auditContext));
        auditLog.setGroupId(resolveGroupId(audit));
        return auditLog;
    }

    private AuditLogMessage buildMessage(Long templateId, String message) {
        AuditLogMessage auditLogMessage = new AuditLogMessage();
        auditLogMessage.setTemplateId(templateId);
        auditLogMessage.setMessage(message);
        return auditLogMessage;
    }

    /**
     * Serializes only the audited method's arguments and outcome - never the actor/client fields
     * already recorded as dedicated {@link AuditLog} columns, which would otherwise be duplicated
     * into {@code data} as a second, independently-driftable source of truth.
     */
    private String serializeData(AuditContext auditContext) {
        try {
            return auditLogArgumentSerializer.serialize(new AuditLogPayload(
                    auditContext.args(), auditContext.result(), auditContext.exception(), auditContext.exceptionThrown()));
        } catch (Exception e) {
            log.warn("Argument serializer threw while building the audit log data payload", e);
            return null;
        }
    }

    /** The reuse-by-name is why {@link AuditGroup#getName()} has a unique constraint. */
    private Long resolveGroupId(Audit audit) {
        if (audit.groupName().isEmpty()) {
            return null;
        }
        try {
            TypedQuery<Long> query = entityManager.createQuery(
                    "select g.id from AuditGroup g where g.name = :name", Long.class);
            query.setParameter("name", audit.groupName());
            return query.getSingleResult();
        } catch (NoResultException e) {
            AuditGroup auditGroup = new AuditGroup();
            auditGroup.setName(audit.groupName());
            entityManager.persist(auditGroup);
            return auditGroup.getId();
        }
    }

    private List<AuditTemplate> findTemplatesByName(List<String> names) {
        TypedQuery<AuditTemplate> query = entityManager.createQuery(
                "select t from AuditTemplate t where t.name in :names", AuditTemplate.class);
        query.setParameter("names", names);
        return query.getResultList();
    }

    private record AuditLogPayload(Object args, Object result, Object exception, boolean exceptionThrown) {
    }
}
