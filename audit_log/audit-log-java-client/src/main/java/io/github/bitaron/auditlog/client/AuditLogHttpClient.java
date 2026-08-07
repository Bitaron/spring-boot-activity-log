package io.github.bitaron.auditlog.client;

import io.github.bitaron.auditlog.client.exception.AuditLogClientAuthenticationException;
import io.github.bitaron.auditlog.client.exception.AuditLogClientBadRequestException;
import io.github.bitaron.auditlog.client.exception.AuditLogClientConnectionException;
import io.github.bitaron.auditlog.client.exception.AuditLogClientException;
import io.github.bitaron.auditlog.client.exception.AuditLogClientServerException;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventResponse;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditQueryResponse;
import org.springframework.http.MediaType;
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

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
        this(RestClient.builder(), baseUrl, apiKey);
    }

    /**
     * Same as {@link #AuditLogHttpClient(String, String)}, but starting from a caller-supplied
     * {@link RestClient.Builder} instead of a fresh {@link RestClient#builder()} - the seam for
     * configuring connect/read timeouts, interceptors, or a custom
     * {@link org.springframework.http.client.ClientHttpRequestFactory} (this class exposes none
     * of those directly). The Protobuf message converter is still added here regardless of what
     * the builder already has configured.
     *
     * @param restClientBuilder the builder to start from; {@code baseUrl} and the Protobuf message
     *                          converter are applied on top of whatever it already has configured
     * @param baseUrl           the server module's base URL, e.g. {@code "https://audit.example.com"}
     * @param apiKey            the value configured as {@code audit.log.server.api-key} on the server
     */
    public AuditLogHttpClient(RestClient.Builder restClientBuilder, String baseUrl, String apiKey) {
        this.apiKey = apiKey;
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .messageConverters(converters -> converters.add(0, new ProtobufHttpMessageConverter()))
                .build();
    }

    /**
     * {@code POST /audit-log/events}. See {@code AuditIngestController} - a {@code 202} response
     * means the event was handed to the server's delivery pipeline, not that it's durably
     * committed yet.
     *
     * @throws AuditLogClientAuthenticationException if the configured API key is missing/invalid
     * @throws AuditLogClientBadRequestException      if the server rejects the request itself
     * @throws AuditLogClientServerException          if the server responds with a {@code 5xx}
     * @throws AuditLogClientConnectionException      if the request never reaches the server
     */
    public AuditEventResponse ingest(AuditEventRequest request) {
        return execute(() -> restClient.post()
                .uri("/audit-log/events")
                .header(API_KEY_HEADER, apiKey)
                .contentType(PROTOBUF)
                .accept(PROTOBUF)
                .body(request)
                .retrieve()
                .body(AuditEventResponse.class));
    }

    /**
     * {@code GET /audit-log/records}. Any parameter may be {@code null} (unfiltered); {@code page}
     * and {@code size} follow the same semantics as {@code AuditLogQueryService.find}, including
     * the {@code audit.log.query.max-page-size} cap on {@code size}. Prefer
     * {@link #query(AuditQueryRequest)} for new code - it uses the same typed request shape the
     * {@code .proto} schema already defines for this endpoint, instead of six positional
     * parameters (several adjacent, easy to transpose).
     *
     * @throws AuditLogClientAuthenticationException if the configured API key is missing/invalid
     * @throws AuditLogClientBadRequestException      if the server rejects the request itself
     * @throws AuditLogClientServerException          if the server responds with a {@code 5xx}
     * @throws AuditLogClientConnectionException      if the request never reaches the server
     */
    public AuditQueryResponse query(String actorId, String auditType, LocalDateTime createdAtFrom,
                                     LocalDateTime createdAtTo, int page, int size) {
        AuditQueryRequest.Builder builder = AuditQueryRequest.newBuilder().setPage(page).setSize(size);
        if (actorId != null) {
            builder.setActorId(actorId);
        }
        if (auditType != null) {
            builder.setAuditType(auditType);
        }
        if (createdAtFrom != null) {
            builder.setCreatedAtFrom(createdAtFrom.toString());
        }
        if (createdAtTo != null) {
            builder.setCreatedAtTo(createdAtTo.toString());
        }
        return query(builder.build());
    }

    /**
     * {@code GET /audit-log/records}, built from the typed {@link AuditQueryRequest} the
     * {@code .proto} schema already defines for this endpoint's query parameters - preferred over
     * the positional overload for new code.
     *
     * @throws AuditLogClientAuthenticationException if the configured API key is missing/invalid
     * @throws AuditLogClientBadRequestException      if the server rejects the request itself
     * @throws AuditLogClientServerException          if the server responds with a {@code 5xx}
     * @throws AuditLogClientConnectionException      if the request never reaches the server
     */
    public AuditQueryResponse query(AuditQueryRequest request) {
        return execute(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/audit-log/records")
                        .queryParamIfPresent("actorId", optionalNonEmpty(request.getActorId()))
                        .queryParamIfPresent("auditType", optionalNonEmpty(request.getAuditType()))
                        .queryParamIfPresent("createdAtFrom", optionalNonEmpty(request.getCreatedAtFrom()))
                        .queryParamIfPresent("createdAtTo", optionalNonEmpty(request.getCreatedAtTo()))
                        .queryParam("page", request.getPage())
                        .queryParam("size", request.getSize())
                        .build())
                .header(API_KEY_HEADER, apiKey)
                .accept(PROTOBUF)
                .retrieve()
                .body(AuditQueryResponse.class));
    }

    private static Optional<String> optionalNonEmpty(String value) {
        return (value == null || value.isEmpty()) ? Optional.empty() : Optional.of(value);
    }

    /**
     * Translates Spring's generic {@code RestClientException} hierarchy into this client's own
     * exception types, so a caller can catch {@link AuditLogClientException} (or a specific
     * subtype) instead of a hierarchy shared with every other use of {@link RestClient} in their
     * application.
     */
    private <T> T execute(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new AuditLogClientAuthenticationException(
                    "Authentication failed calling the audit-log server: " + e.getStatusText(), e);
        } catch (HttpClientErrorException e) {
            throw new AuditLogClientBadRequestException(
                    "The audit-log server rejected the request: " + e.getResponseBodyAsString(), e);
        } catch (HttpServerErrorException e) {
            throw new AuditLogClientServerException(
                    "The audit-log server returned an error: " + e.getStatusText(), e);
        } catch (ResourceAccessException e) {
            throw new AuditLogClientConnectionException(
                    "Failed to reach the audit-log server: " + e.getMessage(), e);
        }
    }
}
