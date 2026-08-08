package io.github.bitaron.auditlog.query;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP17 acceptance: {@link AuditQuery}'s static factories and "wither" methods each produce the
 * expected field combination, with every other field left unfiltered ({@code null}).
 */
class AuditQueryTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 1, 2, 0, 0);

    @Test
    void allIsFullyUnfiltered() {
        assertThat(AuditQuery.all()).isEqualTo(new AuditQuery(null, null, null, null));
    }

    @Test
    void byActorFiltersOnlyOnActor() {
        assertThat(AuditQuery.byActor("actor-1")).isEqualTo(new AuditQuery("actor-1", null, null, null));
    }

    @Test
    void byTypeFiltersOnlyOnAuditType() {
        assertThat(AuditQuery.byType("USER_LOGIN")).isEqualTo(new AuditQuery(null, "USER_LOGIN", null, null));
    }

    @Test
    void byActorAndTypeFiltersOnBoth() {
        assertThat(AuditQuery.byActorAndType("actor-1", "USER_LOGIN"))
                .isEqualTo(new AuditQuery("actor-1", "USER_LOGIN", null, null));
    }

    @Test
    void withCreatedBetweenReplacesOnlyTheDateRange() {
        AuditQuery query = AuditQuery.byActorAndType("actor-1", "USER_LOGIN").withCreatedBetween(FROM, TO);
        assertThat(query).isEqualTo(new AuditQuery("actor-1", "USER_LOGIN", FROM, TO));
    }

    @Test
    void withActorReplacesOnlyTheActor() {
        AuditQuery query = AuditQuery.all().withCreatedBetween(FROM, TO).withActor("actor-1");
        assertThat(query).isEqualTo(new AuditQuery("actor-1", null, FROM, TO));
    }

    @Test
    void withTypeReplacesOnlyTheAuditType() {
        AuditQuery query = AuditQuery.byActor("actor-1").withType("USER_LOGIN");
        assertThat(query).isEqualTo(new AuditQuery("actor-1", "USER_LOGIN", null, null));
    }
}
