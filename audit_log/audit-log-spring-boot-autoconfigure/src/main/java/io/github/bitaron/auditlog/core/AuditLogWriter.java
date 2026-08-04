package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.contract.AuditLogArgumentSerializer;
import io.github.bitaron.auditlog.contract.AuditLogTemplateResolver;
import io.github.bitaron.auditlog.dto.AuditLogClientData;
import io.github.bitaron.auditlog.entity.AuditGroup;
import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.entity.AuditTemplate;
import jakarta.persistence.EntityManager;
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
 * Performs the actual persistence of one {@code @Audit} invocation's audit record(s).
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
    public void persistRequiresNew(Audit audit, AuditLogClientData clientData) {
        doPersist(audit, clientData);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void persistShared(Audit audit, AuditLogClientData clientData) {
        doPersist(audit, clientData);
    }

    private void doPersist(Audit audit, AuditLogClientData clientData) {
        List<String> templateNameList = Arrays.stream(audit.templates())
                .distinct()
                .toList();

        Map<String, AuditTemplate> templatesByName = templateNameList.isEmpty()
                ? Map.of()
                : findTemplatesByName(templateNameList).stream()
                        .collect(Collectors.toMap(AuditTemplate::getName, Function.identity(), (a, b) -> a));

        List<AuditLog> auditLogList = new ArrayList<>();
        LocalDateTime currentTime = LocalDateTime.now(ZoneOffset.UTC);

        if (templateNameList.isEmpty()) {
            // No templates requested: still record that the method ran, just without a rendered message.
            auditLogList.add(buildAuditLog(audit, clientData, currentTime, null, null));
        } else {
            for (String templateName : templateNameList) {
                AuditTemplate auditTemplate = templatesByName.get(templateName);
                if (auditTemplate == null) {
                    log.warn("@Audit references template \"{}\" but no matching audit_template row exists; skipping it",
                            templateName);
                    continue;
                }
                String message = auditLogTemplateResolver.resolveTemplate(
                        auditTemplate.getName(), auditTemplate.getTemplate(), clientData);
                auditLogList.add(buildAuditLog(audit, clientData, currentTime, auditTemplate.getId(), message));
            }
        }

        if (auditLogList.isEmpty()) {
            return;
        }

        // Only created once we know at least one audit row will actually be persisted, so
        // groups no longer accumulate for invocations that end up recording nothing.
        Long groupId = resolveGroupId(audit);
        for (AuditLog auditLog : auditLogList) {
            auditLog.setGroupId(groupId);
            entityManager.persist(auditLog);
        }
    }

    private AuditLog buildAuditLog(Audit audit, AuditLogClientData clientData, LocalDateTime currentTime,
                                    Long templateId, String message) {
        AuditLog auditLog = new AuditLog();
        auditLog.setAuditType(audit.auditType());
        auditLog.setActionName(audit.actionName());
        auditLog.setActionType(audit.actionType());
        auditLog.setActorId(clientData.getActorId());
        auditLog.setActorName(clientData.getActorName());
        auditLog.setClientIp(clientData.getClientIp());
        auditLog.setClientLocation(clientData.getClientLocation());
        auditLog.setUserAgent(clientData.getUserAgent());
        auditLog.setCreatedAt(currentTime);
        auditLog.setTemplateId(templateId);
        auditLog.setMessage(message);
        auditLog.setData(serializeData(clientData));
        return auditLog;
    }

    private String serializeData(AuditLogClientData clientData) {
        try {
            return auditLogArgumentSerializer.serialize(clientData);
        } catch (Exception e) {
            log.warn("Argument serializer threw while building the audit log data payload", e);
            return null;
        }
    }

    private Long resolveGroupId(Audit audit) {
        if (audit.groupName().isEmpty()) {
            return null;
        }
        AuditGroup auditGroup = new AuditGroup();
        auditGroup.setName(audit.groupName());
        entityManager.persist(auditGroup);
        return auditGroup.getId();
    }

    private List<AuditTemplate> findTemplatesByName(List<String> names) {
        TypedQuery<AuditTemplate> query = entityManager.createQuery(
                "select t from AuditTemplate t where t.name in :names", AuditTemplate.class);
        query.setParameter("names", names);
        return query.getResultList();
    }
}
