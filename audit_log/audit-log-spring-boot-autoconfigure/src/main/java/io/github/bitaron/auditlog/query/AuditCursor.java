package io.github.bitaron.auditlog.query;

import java.time.LocalDateTime;

/**
 * A position in the {@code created_at desc, id desc} ordering used by
 * {@link AuditLogQueryService#findAfter}, marking "everything strictly after this row" for the
 * next page. {@code id} is a tie-breaker, not just {@code createdAt}: two rows can share a
 * timestamp (bulk inserts, low-resolution clocks), and without it, rows sharing the cursor's exact
 * timestamp could be skipped or repeated across pages.
 * <p>
 * Construct one from the last {@link AuditRecord} of the previous page -
 * {@code new AuditCursor(lastRecord.createdAt(), lastRecord.id())} - or pass {@code null} to
 * {@code findAfter} to fetch the first page.
 *
 * @param createdAt the {@code created_at} of the last row already seen
 * @param id         the {@code id} of the last row already seen (tie-breaker for equal timestamps)
 */
public record AuditCursor(LocalDateTime createdAt, Long id) {
}
