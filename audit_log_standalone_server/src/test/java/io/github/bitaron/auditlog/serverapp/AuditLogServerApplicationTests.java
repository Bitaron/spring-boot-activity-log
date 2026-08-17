package io.github.bitaron.auditlog.serverapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-loads smoke test: proves this app's own composition root - real embedded H2, real
 * {@code AuditLogServerAutoConfiguration} wiring, the {@code X-API-Key} filter - actually boots,
 * so {@code mvn clean install} from the repo root exercises this module instead of only compiling
 * it (see AGENTS.md's "must be green" build rule). Deeper endpoint-behavior coverage
 * (JSON/Protobuf round trips, missing-key rejection) already lives in
 * {@code AuditLogServerIntegrationTest} in the library module - not duplicated here.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "audit.log.multi-tenancy.enabled=true",
                "audit.log.server.api-keys.default=test-api-key"
        })
class AuditLogServerApplicationTests {

    @LocalServerPort
    private int port;

    @Test
    void ingestEndpointIsReachableWithTheConfiguredApiKey() {
        TestRestTemplate restTemplate = new TestRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "test-api-key");
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"auditType\":\"smoke-test\"}", headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/audit-log/events", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }
}
