package io.github.bitaron.auditlog.client;

import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryResponse;
import org.springframework.http.MediaType;
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Typed Java client for the {@code audit-log-spring-boot-server} REST module, built on Spring's
 * {@link RestClient} (the standard synchronous HTTP client since Boot 3.2) rather than a
 * hand-rolled HTTP call. This is the module a Java consumer of server mode actually depends on -
 * the generated {@code AuditEventRequest}/{@code AuditQueryResponse} types come from
 * {@code audit-log-server-proto}; only this thin wrapper is hand-written.
 * <p>
 * Not a Spring Boot starter itself and registers no beans - construct one directly wherever it's
 * needed, in a Spring application or otherwise. For a language other than Java, see
 * {@code docs/CLIENT_CODEGEN.md} at the repository root: the same {@code .proto} schema this
 * class is generated from is the source of truth for any {@code protoc}-generated client.
 */
public final class AuditLogHttpClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");

    private final RestClient restClient;
    private final String apiKey;

    /**
     * @param baseUrl the server module's base URL, e.g. {@code "https://audit.example.com"}
     * @param apiKey  the value configured as {@code audit.log.server.api-key} on the server
     */
    public AuditLogHttpClient(String baseUrl, String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .messageConverters(converters -> converters.add(0, new ProtobufHttpMessageConverter()))
                .build();
    }

    /**
     * {@code POST /audit-log/events}. See {@code AuditIngestController} - a {@code 202} response
     * means the event was handed to the server's delivery pipeline, not that it's durably
     * committed yet.
     */
    public AuditEventResponse ingest(AuditEventRequest request) {
        return restClient.post()
                .uri("/audit-log/events")
                .header(API_KEY_HEADER, apiKey)
                .contentType(PROTOBUF)
                .accept(PROTOBUF)
                .body(request)
                .retrieve()
                .body(AuditEventResponse.class);
    }

    /**
     * {@code GET /audit-log/records}. Any parameter may be {@code null} (unfiltered); {@code page}
     * and {@code size} follow the same semantics as {@code AuditLogQueryService.find}, including
     * the {@code audit.log.query.max-page-size} cap on {@code size}.
     */
    public AuditQueryResponse query(String actorId, String auditType, LocalDateTime createdAtFrom,
                                     LocalDateTime createdAtTo, int page, int size) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/audit-log/records")
                        .queryParamIfPresent("actorId", Optional.ofNullable(actorId))
                        .queryParamIfPresent("auditType", Optional.ofNullable(auditType))
                        .queryParamIfPresent("createdAtFrom", Optional.ofNullable(createdAtFrom))
                        .queryParamIfPresent("createdAtTo", Optional.ofNullable(createdAtTo))
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build())
                .header(API_KEY_HEADER, apiKey)
                .accept(PROTOBUF)
                .retrieve()
                .body(AuditQueryResponse.class);
    }
}
