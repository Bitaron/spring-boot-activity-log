package io.github.bitaron.auditlog.client;

import io.github.bitaron.auditlog.client.exception.AuditLogClientAuthenticationException;
import io.github.bitaron.auditlog.client.exception.AuditLogClientBadRequestException;
import io.github.bitaron.auditlog.client.exception.AuditLogClientConnectionException;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP17 acceptance: {@link AuditLogHttpClient} translates the server's HTTP error responses (and a
 * failure to reach it at all) into this client's own typed exceptions, not Spring's generic
 * {@code RestClientException} hierarchy.
 */
@SpringBootTest(
        classes = ClientTestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.generate-unique-name=true",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "audit.log.server.enabled=true",
                "audit.log.multi-tenancy.enabled=true",
                "audit.log.server.api-keys.client-test-tenant=client-test-key",
                "audit.log.query.max-page-size=10"
        })
class AuditLogHttpClientErrorHandlingTest {

    @LocalServerPort
    private int port;

    @Test
    void wrongApiKeyThrowsAuthenticationException() {
        AuditLogHttpClient client = new AuditLogHttpClient("http://localhost:" + port, "not-the-configured-key");

        assertThatThrownBy(() -> client.ingest(AuditEventRequest.newBuilder()
                .setAuditType("error-handling-test")
                .build()))
                .isInstanceOf(AuditLogClientAuthenticationException.class);
    }

    @Test
    void oversizedPageThrowsBadRequestExceptionWithServerMessagePreserved() {
        AuditLogHttpClient client = new AuditLogHttpClient("http://localhost:" + port, "client-test-key");

        assertThatThrownBy(() -> client.query(AuditQueryRequest.newBuilder().setPage(0).setSize(11).build()))
                .isInstanceOf(AuditLogClientBadRequestException.class)
                .hasMessageContaining("11")
                .hasMessageContaining("audit.log.query.max-page-size");
    }

    @Test
    void unreachableServerThrowsConnectionException() {
        AuditLogHttpClient client = new AuditLogHttpClient("http://localhost:1", "client-test-key");

        assertThatThrownBy(() -> client.ingest(AuditEventRequest.newBuilder()
                .setAuditType("error-handling-test")
                .build()))
                .isInstanceOf(AuditLogClientConnectionException.class);
    }

    @Test
    void validRequestStillSucceedsWithTheSamePort() {
        AuditLogHttpClient client = new AuditLogHttpClient("http://localhost:" + port, "client-test-key");

        assertThat(client.ingest(AuditEventRequest.newBuilder()
                .setAuditType("error-handling-sanity-check")
                .build()).getAccepted()).isTrue();
    }
}
