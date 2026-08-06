package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.contract.AuditLogArgumentSerializer;
import io.github.bitaron.auditlog.contract.AuditLogTemplateResolver;
import io.github.bitaron.auditlog.contract.AuditTemplateSource;
import io.github.bitaron.auditlog.entity.AuditGroup;
import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.entity.AuditLogMessage;
import io.github.bitaron.auditlog.entity.AuditOutcome;
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
import java.util.Optional;

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
    private final List<AuditTemplateSource> auditTemplateSources;

    public AuditLogWriter(EntityManager entityManager,
                           AuditLogTemplateResolver auditLogTemplateResolver,
                           AuditLogArgumentSerializer auditLogArgumentSerializer,
                           List<AuditTemplateSource> auditTemplateSources) {
        this.entityManager = entityManager;
        this.auditLogTemplateResolver = auditLogTemplateResolver;
        this.auditLogArgumentSerializer = auditLogArgumentSerializer;
        this.auditTemplateSources = auditTemplateSources;
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

        List<AuditLogMessage> messages = new ArrayList<>();
        for (String templateName : templateNames) {
            Optional<String> templateContent = findTemplate(templateName);
            if (templateContent.isEmpty()) {
                log.warn("@Audit references template \"{}\" but no configured AuditTemplateSource resolves it; skipping it",
                        templateName);
                continue;
            }
            String message = auditLogTemplateResolver.resolveTemplate(templateName, templateContent.get(), auditContext);
            messages.add(buildMessage(templateName, message));
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
        auditLog.setTenantId(auditContext.tenantId());
        auditLog.setData(serializeData(auditContext));
        auditLog.setGroupId(resolveGroupId(audit));
        return auditLog;
    }

    private AuditLogMessage buildMessage(String templateName, String message) {
        AuditLogMessage auditLogMessage = new AuditLogMessage();
        auditLogMessage.setTemplateName(templateName);
        auditLogMessage.setMessage(message);
        return auditLogMessage;
    }

    /** Tries every configured {@link AuditTemplateSource} in order, returning the first hit. */
    private Optional<String> findTemplate(String name) {
        for (AuditTemplateSource source : auditTemplateSources) {
            Optional<String> template = source.findTemplate(name);
            if (template.isPresent()) {
                return template;
            }
        }
        return Optional.empty();
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

    /** The reuse-by-name is why {@link AuditGroup}'s name column has a unique constraint. */
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

    private record AuditLogPayload(Object args, Object result, Object exception, boolean exceptionThrown) {
    }
}
