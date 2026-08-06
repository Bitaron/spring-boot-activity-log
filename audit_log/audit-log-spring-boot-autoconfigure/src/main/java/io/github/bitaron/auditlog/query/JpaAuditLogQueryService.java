package io.github.bitaron.auditlog.query;

import io.github.bitaron.auditlog.contract.AuditTenantResolver;
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
            + "a.actionType, a.actionName, a.createdAt, a.outcome, a.durationMs, a.traceId, a.data, "
            + "a.groupId, a.tenantId) "
            + "from AuditLog a";

    /**
     * The only {@link AuditRecord}/{@code AuditLog} properties {@link #find}'s {@code Sort} may
     * reference - all back an index on {@code audit_log} (see {@code AuditLog}'s
     * {@code @Table(indexes = ...)}), so every accepted sort stays index-backed rather than
     * forcing a full sort of the filtered result set.
     */
    private static final Set<String> SORTABLE_PROPERTIES = Set.of("id", "createdAt", "actorId", "auditType");

    private final EntityManager entityManager;
    private final int maxPageSize;
    private final AuditTenantResolver auditTenantResolver;
    private final boolean multiTenancyEnabled;

    public JpaAuditLogQueryService(EntityManager entityManager, int maxPageSize) {
        this(entityManager, maxPageSize, null, false);
    }

    /**
     * @param auditTenantResolver resolves the current tenant on every read; may be {@code null}
     *                            (must be, unless {@code multiTenancyEnabled})
     * @param multiTenancyEnabled when {@code true}, every {@link #find}/{@link #findAfter} call is
     *                            unconditionally scoped to {@code auditTenantResolver}'s result,
     *                            regardless of what {@link AuditQuery} itself asked for - this,
     *                            not a caller-suppliable filter field, is what makes it structurally
     *                            hard for a future read to accidentally cross tenants. Fails closed
     *                            (throws) rather than running unscoped if no tenant resolves.
     */
    public JpaAuditLogQueryService(EntityManager entityManager, int maxPageSize,
                                    AuditTenantResolver auditTenantResolver, boolean multiTenancyEnabled) {
        this.entityManager = entityManager;
        this.maxPageSize = maxPageSize;
        this.auditTenantResolver = auditTenantResolver;
        this.multiTenancyEnabled = multiTenancyEnabled;
    }

    @Override
    public Page<AuditRecord> find(AuditQuery query, Pageable pageable) {
        requirePageSizeWithinLimit(pageable.getPageSize());
        String tenantId = resolveMandatoryTenantId();
        String whereClause = whereClause(predicateList(query, tenantId));

        TypedQuery<AuditRecord> selectQuery = entityManager.createQuery(
                SELECT + whereClause + orderByClause(pageable.getSort()), AuditRecord.class);
        TypedQuery<Long> countQuery = entityManager.createQuery(
                "select count(a) from AuditLog a" + whereClause, Long.class);
        bindParameters(selectQuery, namedParameters(query, tenantId));
        bindParameters(countQuery, namedParameters(query, tenantId));

        selectQuery.setFirstResult((int) pageable.getOffset());
        selectQuery.setMaxResults(pageable.getPageSize());

        List<AuditRecord> content = selectQuery.getResultList();
        return new PageImpl<>(content, pageable, countQuery.getSingleResult());
    }

    @Override
    public List<AuditRecord> findAfter(AuditQuery query, AuditCursor cursor, int limit) {
        requirePageSizeWithinLimit(limit);
        String tenantId = resolveMandatoryTenantId();
        List<String> predicates = new ArrayList<>(predicateList(query, tenantId));
        Map<String, Object> parameters = new LinkedHashMap<>(namedParameters(query, tenantId));
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

    /**
     * Resolves the tenant every predicate in this call must be scoped to, or {@code null} when
     * multi-tenancy is disabled (in which case no tenant predicate is added at all - identical
     * behavior to before this feature existed). Called once per {@link #find}/{@link #findAfter}
     * invocation - not cached - so a resolver backed by per-request state (the default, header-based
     * one included) is re-evaluated for every query.
     */
    private String resolveMandatoryTenantId() {
        if (!multiTenancyEnabled) {
            return null;
        }
        String tenantId = auditTenantResolver != null ? auditTenantResolver.resolveTenantId() : null;
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("audit.log.multi-tenancy.enabled=true but no tenant could be "
                    + "resolved for this query; refusing to run an unscoped read. Configure an "
                    + "AuditTenantResolver bean (or the request header it reads) to resolve a tenant "
                    + "before querying.");
        }
        return tenantId;
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

    /**
     * The mandatory {@code tenantId} predicate (when non-null) is prepended, ahead of every
     * caller-suppliable filter below - it is not one of them, and is not skippable by any
     * {@link AuditQuery} a caller constructs.
     */
    private List<String> predicateList(AuditQuery query, String tenantId) {
        List<String> predicates = new ArrayList<>();
        if (tenantId != null) {
            predicates.add("a.tenantId = :__tenantId");
        }
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

    private Map<String, Object> namedParameters(AuditQuery query, String tenantId) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (tenantId != null) {
            parameters.put("__tenantId", tenantId);
        }
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
