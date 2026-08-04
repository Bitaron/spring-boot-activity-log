package io.github.bitaron.auditlog.query;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Default {@link AuditLogQueryService}, backed by a plain {@link EntityManager} query. */
public class JpaAuditLogQueryService implements AuditLogQueryService {

    private static final String SELECT = "select new io.github.bitaron.auditlog.query.AuditRecord("
            + "a.id, a.auditType, a.actorId, a.actorName, a.clientIp, a.clientLocation, a.userAgent, "
            + "a.actionType, a.actionName, a.createdAt, a.outcome, a.durationMs, a.traceId, a.data, a.groupId) "
            + "from AuditLog a";

    private final EntityManager entityManager;

    public JpaAuditLogQueryService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<AuditRecord> find(AuditQuery query, Pageable pageable) {
        String whereClause = whereClause(query);

        TypedQuery<AuditRecord> selectQuery = entityManager.createQuery(
                SELECT + whereClause + " order by a.createdAt desc", AuditRecord.class);
        TypedQuery<Long> countQuery = entityManager.createQuery(
                "select count(a) from AuditLog a" + whereClause, Long.class);
        bindParameters(selectQuery, query);
        bindParameters(countQuery, query);

        selectQuery.setFirstResult((int) pageable.getOffset());
        selectQuery.setMaxResults(pageable.getPageSize());

        List<AuditRecord> content = selectQuery.getResultList();
        return new PageImpl<>(content, pageable, countQuery.getSingleResult());
    }

    private String whereClause(AuditQuery query) {
        List<String> predicates = new ArrayList<>();
        if (query.actorId() != null) {
            predicates.add("a.actorId = :actorId");
        }
        if (query.auditType() != null) {
            predicates.add("a.auditType = :auditType");
        }
        if (query.createdAtFrom() != null) {
            predicates.add("a.createdAt >= :createdAtFrom");
        }
        if (query.createdAtTo() != null) {
            predicates.add("a.createdAt <= :createdAtTo");
        }
        return predicates.isEmpty() ? "" : " where " + String.join(" and ", predicates);
    }

    private void bindParameters(Query jpaQuery, AuditQuery query) {
        for (Map.Entry<String, Object> parameter : namedParameters(query).entrySet()) {
            jpaQuery.setParameter(parameter.getKey(), parameter.getValue());
        }
    }

    private Map<String, Object> namedParameters(AuditQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (query.actorId() != null) {
            parameters.put("actorId", query.actorId());
        }
        if (query.auditType() != null) {
            parameters.put("auditType", query.auditType());
        }
        if (query.createdAtFrom() != null) {
            parameters.put("createdAtFrom", query.createdAtFrom());
        }
        if (query.createdAtTo() != null) {
            parameters.put("createdAtTo", query.createdAtTo());
        }
        return parameters;
    }
}
