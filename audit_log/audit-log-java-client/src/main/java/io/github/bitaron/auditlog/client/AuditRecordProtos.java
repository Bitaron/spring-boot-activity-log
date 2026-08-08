package io.github.bitaron.auditlog.client;

import io.github.bitaron.auditlog.server.proto.v1.AuditRecordProto;

import java.time.LocalDateTime;

/**
 * Conversion helpers for {@link AuditRecordProto} - a generated type, so this can't be a method on
 * the class itself. {@link AuditRecordProto#getCreatedAt()} is a raw ISO-8601 wire string (see the
 * field's own {@code .proto} comment); every other caller of this client otherwise has to parse it
 * back to a {@link LocalDateTime} themselves.
 */
public final class AuditRecordProtos {

    private AuditRecordProtos() {
    }

    /**
     * @return {@code record.getCreatedAt()} parsed as a {@link LocalDateTime}, or {@code null} if
     * unset (the proto3 default for an unset {@code string} field is {@code ""}, not a real value)
     */
    public static LocalDateTime createdAt(AuditRecordProto record) {
        String raw = record.getCreatedAt();
        return raw.isEmpty() ? null : LocalDateTime.parse(raw);
    }
}
