package io.github.bitaron.auditlog.query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The supported read API for audit records. Introduced so consuming applications stop querying
 * the {@code AuditLog} JPA entity directly - which would otherwise be the only way to read audit
 * records, freezing the persistence model as public API.
 *
 * @see AuditRecord
 * @see AuditQuery
 */
public interface AuditLogQueryService {

    /**
     * Finds audit records matching {@code query}, most recent first.
     *
     * @param query    filter criteria; use {@link AuditQuery#all()} for no filtering
     * @param pageable pagination (and, if the caller wants a different order, sort)
     * @return the matching page of records
     */
    Page<AuditRecord> find(AuditQuery query, Pageable pageable);
}
