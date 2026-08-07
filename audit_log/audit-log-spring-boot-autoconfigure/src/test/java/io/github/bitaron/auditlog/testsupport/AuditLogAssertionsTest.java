package io.github.bitaron.auditlog.testsupport;

import io.github.bitaron.auditlog.entity.AuditOutcome;
import io.github.bitaron.auditlog.query.AuditLogQueryService;
import io.github.bitaron.auditlog.query.AuditQuery;
import io.github.bitaron.auditlog.query.AuditRecord;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * WP17 acceptance: {@link AuditLogAssertions#awaitRecords} returns as soon as enough records
 * appear, and times out with an {@link AssertionError} (not silently returning too few) when they
 * never do.
 */
class AuditLogAssertionsTest {

    @Test
    void returnsImmediatelyWhenEnoughRecordsAreAlreadyPresent() {
        AuditLogQueryService queryService = Mockito.mock(AuditLogQueryService.class);
        AuditRecord record = record();
        when(queryService.find(any(), any())).thenReturn(new PageImpl<>(List.of(record)));

        List<AuditRecord> result = AuditLogAssertions.awaitRecords(
                queryService, AuditQuery.all(), 1, Duration.ofSeconds(1));

        assertThat(result).containsExactly(record);
    }

    @Test
    void awaitRecordReturnsTheFirstMatch() {
        AuditLogQueryService queryService = Mockito.mock(AuditLogQueryService.class);
        AuditRecord record = record();
        when(queryService.find(any(), any())).thenReturn(new PageImpl<>(List.of(record)));

        assertThat(AuditLogAssertions.awaitRecord(queryService, AuditQuery.all(), Duration.ofSeconds(1)))
                .isEqualTo(record);
    }

    @Test
    void timesOutWithAnAssertionErrorWhenNothingEverAppears() {
        AuditLogQueryService queryService = Mockito.mock(AuditLogQueryService.class);
        Page<AuditRecord> empty = new PageImpl<>(List.of());
        when(queryService.find(any(), any())).thenReturn(empty);

        assertThatThrownBy(() -> AuditLogAssertions.awaitRecords(
                queryService, AuditQuery.all(), 1, Duration.ofMillis(200)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected at least 1 audit record");
    }

    private static AuditRecord record() {
        return new AuditRecord(1L, "TEST", "actor-1", "Actor One", "127.0.0.1", "", "",
                "READ", "read", java.time.LocalDateTime.now(), AuditOutcome.SUCCESS, 5L, null, null, null, null);
    }
}
