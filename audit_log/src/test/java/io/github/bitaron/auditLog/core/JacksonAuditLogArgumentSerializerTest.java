package io.github.bitaron.auditLog.core;

import io.github.bitaron.auditLog.properties.AuditLogProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JacksonAuditLogArgumentSerializerTest {

    @Test
    void nullReturnsNull() {
        assertThat(newSerializer(new AuditLogProperties()).serialize(null)).isNull();
    }

    @Test
    void servletRequestIsNeverReflectedOver() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenThrow(new UnsupportedOperationException("must never be called"));

        String json = newSerializer(new AuditLogProperties()).serialize(request);

        assertThat(json).contains("not serialized");
    }

    @Test
    void throwableIsSerializedAsCompactTypeAndMessageOnly() {
        RuntimeException exception = new RuntimeException("boom");

        String json = newSerializer(new AuditLogProperties()).serialize(exception);

        assertThat(json).contains("\"type\":\"java.lang.RuntimeException\"");
        assertThat(json).contains("\"message\":\"boom\"");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void maskedFieldsAreRedactedAtAnyDepth() {
        record Nested(String token) {
        }
        record Outer(String password, Nested nested) {
        }

        AuditLogProperties properties = new AuditLogProperties();
        properties.setMaskedFields(Set.of("password", "token"));

        String json = newSerializer(properties).serialize(new Outer("s3cr3t", new Nested("t0k3n")));

        assertThat(json).doesNotContain("s3cr3t").doesNotContain("t0k3n");
        assertThat(json).contains("\"password\":\"***\"").contains("\"token\":\"***\"");
    }

    @Test
    void oversizedPayloadIsTruncatedIntoAValidJsonEnvelope() {
        AuditLogProperties properties = new AuditLogProperties();
        properties.setMaxSerializedDataLength(20);

        String json = newSerializer(properties).serialize("a".repeat(1000));

        assertThat(json).contains("\"truncated\":true");
        // Must still be valid, parseable JSON - the point of the fix (see javadoc on truncate()).
        assertThat(json).doesNotEndWith("(truncated)\"").startsWith("{").endsWith("}");
    }

    private JacksonAuditLogArgumentSerializer newSerializer(AuditLogProperties properties) {
        return new JacksonAuditLogArgumentSerializer(properties);
    }
}
