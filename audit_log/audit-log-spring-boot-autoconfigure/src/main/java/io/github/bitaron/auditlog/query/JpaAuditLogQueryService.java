package io.github.bitaron.auditlog.query;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Default {@link AuditLogQueryService}, backed by a plain {@link EntityManager} query. */
public class JpaAuditLogQueryService implements AuditLogQueryService {

    private static final String SELECT = "select new io.github.bitaron.auditlog.query.AuditRecord("
            + "a.id, a.auditType, a.actorId, a.actorName, a.clientIp, a.clientLocation, a.userAgent, "
            + "a.actionType, a.actionName, a.createdAt, a.outcome, a.durationMs, a.traceId, a.data, a.groupId) "
            + "from AuditLog a";

    /**
     * The only {@link AuditRecord}/{@code AuditLog} properties {@link #find}'s {@code Sort} may
     * reference - all four back an index on {@code audit_log} (see {@code AuditLog}'s
     * {@code @Table(indexes = ...)}), so every accepted sort stays index-backed rather than
     * forcing a full sort of the filtered result set.
     */
    private static final Set<String> SORTABLE_PROPERTIES = Set.of("id", "createdAt", "actorId", "auditType");

    private final EntityManager entityManager;
    private final int maxPageSize;

    public JpaAuditLogQueryService(EntityManager entityManager, int maxPageSize) {
        this.entityManager = entityManager;
        this.maxPageSize = maxPageSize;
    }

    @Override
    public Page<AuditRecord> find(AuditQuery query, Pageable pageable) {
        requirePageSizeWithinLimit(pageable.getPageSize());
        String whereClause = whereClause(predicateList(query));

        TypedQuery<AuditRecord> selectQuery = entityManager.createQuery(
                SELECT + whereClause + orderByClause(pageable.getSort()), AuditRecord.class);
        TypedQuery<Long> countQuery = entityManager.createQuery(
                "select count(a) from AuditLog a" + whereClause, Long.class);
        bindParameters(selectQuery, namedParameters(query));
        bindParameters(countQuery, namedParameters(query));

        selectQuery.setFirstResult((int) pageable.getOffset());
        selectQuery.setMaxResults(pageable.getPageSize());

        List<AuditRecord> content = selectQuery.getResultList();
        return new PageImpl<>(content, pageable, countQuery.getSingleResult());
    }

    @Override
    public List<AuditRecord> findAfter(AuditQuery query, AuditCursor cursor, int limit) {
        requirePageSizeWithinLimit(limit);
        List<String> predicates = new ArrayList<>(predicateList(query));
        Map<String, Object> parameters = new LinkedHashMap<>(namedParameters(query));
        if (cursor != null) {
            // Standard JPQL, not the (a.createdAt, a.id) < (:x, :y) row-value tuple syntax the
            // acceptance criteria describe conceptually: HQL/JPQL tuple comparisons aren't
            // portable across every JPA provider/version this starter supports, and this OR-
            // expansion is exactly equivalent - "strictly before the cursor's timestamp, or tied
            // on timestamp and strictly before its id" - for the created_at desc, id desc order
            // below.
            predicates.add("(a.createdAt < :cursorCreatedAt "
                    + "or (a.createdAt = :cursorCreatedAt and a.id < :cursorId))");
            parameters.put("cursorCreatedAt", cursor.createdAt());
            parameters.put("cursorId", cursor.id());
        }

        TypedQuery<AuditRecord> selectQuery = entityManager.createQuery(
                SELECT + whereClause(predicates) + " order by a.createdAt desc, a.id desc", AuditRecord.class);
        bindParameters(selectQuery, parameters);
        selectQuery.setMaxResults(limit);
        return selectQuery.getResultList();
    }

    private void requirePageSizeWithinLimit(int pageSize) {
        if (pageSize > maxPageSize) {
            throw new IllegalArgumentException("Requested page size " + pageSize
                    + " exceeds audit.log.query.max-page-size (" + maxPageSize + ")");
        }
    }

    private String orderByClause(Sort sort) {
        if (sort.isUnsorted()) {
            return " order by a.createdAt desc";
        }
        List<String> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new IllegalArgumentException("Cannot sort AuditLogQueryService.find by \""
                        + order.getProperty() + "\"; supported properties are " + SORTABLE_PROPERTIES);
            }
            orders.add("a." + order.getProperty() + " " + (order.isAscending() ? "asc" : "desc"));
        }
        return " order by " + String.join(", ", orders);
    }

    private List<String> predicateList(AuditQuery query) {
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
        return predicates;
    }

    private String whereClause(List<String> predicates) {
        return predicates.isEmpty() ? "" : " where " + String.join(" and ", predicates);
    }

    private void bindParameters(Query jpaQuery, Map<String, Object> parameters) {
        for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
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
