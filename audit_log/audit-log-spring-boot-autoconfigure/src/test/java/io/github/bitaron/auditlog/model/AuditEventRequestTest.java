package io.github.bitaron.auditlog.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP17 acceptance: {@link AuditEventRequest.Builder} produces the exact same record as the
 * canonical (positional) constructor, and the {@code success}/{@code failure} convenience methods
 * set the right combination of {@code result}/{@code exception}/{@code exceptionThrown}.
 */
class AuditEventRequestTest {

    @Test
    void builderProducesFieldIdenticalResultToCanonicalConstructor() {
        AuditEventRequest viaBuilder = AuditEventRequest.builder("USER_LOGIN")
                .actionName("login")
                .actionType("READ")
                .groupName("auth")
                .templates(List.of("login-template"))
                .actorId("actor-1")
                .actorName("Alice")
                .clientIp("127.0.0.1")
                .clientLocation("US")
                .userAgent("curl/8.0")
                .args("{}")
                .result("ok")
                .durationMillis(42L)
                .traceId("trace-1")
                .tenantId("tenant-1")
                .build();

        AuditEventRequest viaConstructor = new AuditEventRequest("USER_LOGIN", "login", "READ", "auth",
                List.of("login-template"), "actor-1", "Alice", "127.0.0.1", "US", "curl/8.0",
                "{}", "ok", null, false, 42L, "trace-1", "tenant-1");

        assertThat(viaBuilder).isEqualTo(viaConstructor);
    }

    @Test
    void builderWithOnlyRequiredFieldMatchesTheSameDefaultsAsTheCanonicalConstructor() {
        AuditEventRequest viaBuilder = AuditEventRequest.builder("USER_LOGIN").build();
        AuditEventRequest viaConstructor = new AuditEventRequest("USER_LOGIN", null, null, null,
                null, null, null, null, null, null, null, null, null, false, 0L, null, null);

        assertThat(viaBuilder).isEqualTo(viaConstructor);
    }

    @Test
    void successSetsResultAndClearsAnyPreviouslySetException() {
        AuditEventRequest request = AuditEventRequest.builder("ORDER_PLACED")
                .failure("boom")
                .success("order-123")
                .build();

        assertThat(request.result()).isEqualTo("order-123");
        assertThat(request.exception()).isNull();
        assertThat(request.exceptionThrown()).isFalse();
    }

    @Test
    void failureSetsExceptionAndClearsAnyPreviouslySetResult() {
        AuditEventRequest request = AuditEventRequest.builder("ORDER_PLACED")
                .success("order-123")
                .failure("boom")
                .build();

        assertThat(request.result()).isNull();
        assertThat(request.exception()).isEqualTo("boom");
        assertThat(request.exceptionThrown()).isTrue();
    }

    @Test
    void blankAuditTypeStillThrowsAtBuildTime() {
        assertThatThrownBy(() -> AuditEventRequest.builder(" ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auditType");
    }
}
