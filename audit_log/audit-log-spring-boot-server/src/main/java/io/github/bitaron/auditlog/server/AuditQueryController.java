package io.github.bitaron.auditlog.server;

import io.github.bitaron.auditlog.query.AuditCursor;
import io.github.bitaron.auditlog.query.AuditLogQueryService;
import io.github.bitaron.auditlog.query.AuditQuery;
import io.github.bitaron.auditlog.query.AuditRecord;
import io.github.bitaron.auditlog.server.proto.v1.AuditCursorQueryResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditRecordProto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code GET /audit-log/records}: a thin HTTP wrapper around {@link AuditLogQueryService#find} -
 * the page-size cap from {@code audit.log.query.max-page-size} applies here too, since both paths
 * share the same query service (an oversized {@code size} surfaces as {@code 400}, via
 * {@link AuditServerExceptionHandler}).
 */
@RestController
@RequestMapping("/audit-log/records")
public class AuditQueryController {

    private final AuditLogQueryService auditLogQueryService;

    public AuditQueryController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    @GetMapping
    public AuditQueryResponse query(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String auditType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AuditQuery query = new AuditQuery(actorId, auditType, createdAtFrom, createdAtTo);
        Page<AuditRecord> result = auditLogQueryService.find(query, PageRequest.of(page, size));
        List<AuditRecordProto> records = result.getContent().stream().map(ProtoMapper::toRecordProto).toList();
        return ProtoMapper.toQueryResponse(records, result.getTotalElements(), page, size);
    }

    /**
     * {@code GET /audit-log/records/after}: a thin HTTP wrapper around
     * {@link AuditLogQueryService#findAfter} (WP11's keyset pagination), so a remote caller isn't
     * stuck with {@link #query}'s offset pagination once a table is large enough that the "discard
     * everything before this page" cost of {@code OFFSET}/{@code LIMIT} starts to matter - see
     * {@code docs/SCALING.md}. {@code cursorCreatedAt}/{@code cursorId} must both be supplied
     * together (the position of the last row already seen), or neither (first page).
     */
    @GetMapping("/after")
    public AuditCursorQueryResponse queryAfter(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String auditType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursorCreatedAt,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(defaultValue = "50") int limit) {
        if ((cursorCreatedAt == null) != (cursorId == null)) {
            throw new IllegalArgumentException(
                    "cursorCreatedAt and cursorId must both be supplied together, or neither, for the first page");
        }
        AuditQuery query = new AuditQuery(actorId, auditType, createdAtFrom, createdAtTo);
        AuditCursor cursor = cursorCreatedAt == null ? null : new AuditCursor(cursorCreatedAt, cursorId);
        List<AuditRecordProto> records = auditLogQueryService.findAfter(query, cursor, limit).stream()
                .map(ProtoMapper::toRecordProto).toList();
        return ProtoMapper.toCursorQueryResponse(records);
    }
}
