package io.github.bitaron.auditlog.query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

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
     * Finds audit records matching {@code query}, most recent first by default (or by
     * {@code pageable.getSort()} if set - restricted to a whitelist of indexed/filterable
     * properties, see the implementation).
     * <p>
     * Offset pagination: computing page N still requires scanning and discarding the N-1 pages
     * before it, which degrades on a large, growing table. Prefer {@link #findAfter} once that
     * cost matters - this method remains the simpler choice for UI-style "page 1, 2, 3..."
     * navigation over a bounded result set.
     *
     * @param query    filter criteria; use {@link AuditQuery#all()} for no filtering
     * @param pageable pagination (and, if the caller wants a different order, sort); rejected if
     *                 the page size exceeds the configured maximum
     * @return the matching page of records
     * @throws IllegalArgumentException if the page size exceeds {@code audit.log.query.max-page-size}
     *                                   or the sort references an unsupported property
     */
    Page<AuditRecord> find(AuditQuery query, Pageable pageable);

    /**
     * Keyset ("seek") pagination: finds up to {@code limit} audit records matching {@code query},
     * strictly after {@code cursor} in {@code created_at desc, id desc} order. Unlike {@link #find},
     * cost is independent of how deep into the result set {@code cursor} is - there is no offset to
     * skip - which is what makes this the right choice once a table is large enough that offset
     * pagination's "discard everything before this page" cost starts to matter.
     *
     * @param query  filter criteria; use {@link AuditQuery#all()} for no filtering
     * @param cursor the position to resume after, or {@code null} to fetch the first page
     * @param limit  maximum records to return; rejected if it exceeds the configured maximum
     * @return up to {@code limit} matching records, most recent first; pass
     *         {@code new AuditCursor(lastRecord.createdAt(), lastRecord.id())} of the last element
     *         back in to fetch the next page, or treat a result smaller than {@code limit} as the
     *         last page
     * @throws IllegalArgumentException if {@code limit} exceeds {@code audit.log.query.max-page-size}
     */
    List<AuditRecord> findAfter(AuditQuery query, AuditCursor cursor, int limit);
}
