# audit-log-java-client

Typed Java client for the [`audit-log-spring-boot-server`](../audit-log-spring-boot-server) REST
module, built on Spring's `RestClient`. Depends on nothing beyond `spring-web` - usable from a
plain Java application, not just a Spring Boot one. For automatic Spring Boot registration (a
`@Bean`, config-driven), see
[`audit-log-java-client-spring-boot-starter`](../audit-log-java-client-spring-boot-starter) instead
of wiring this module directly.

## Usage

```java
AuditLogHttpClient client = new AuditLogHttpClient("https://audit.example.com", "<tenant-api-key>");

client.ingest(AuditEventRequest.newBuilder()
        .setAuditType("PAYMENT")
        .setActionName("capture")
        .setActorId("user-123")
        .build());

AuditQueryResponse page = client.query(AuditQueryRequest.newBuilder()
        .setAuditType("PAYMENT")
        .setPage(0)
        .setSize(50)
        .build());
```

`apiKey` is whichever secret is configured under `audit.log.server.api-keys.<tenantId>` on the
server - it determines which tenant this client acts as (see the server module's README's
"Multi-tenancy" section). The 6-parameter positional `query(...)` overload still exists for
backward-compatible call sites, but `query(AuditQueryRequest)` is preferred for new code - it uses
the same typed shape the `.proto` schema already defines for this endpoint, instead of several
adjacent, easy-to-transpose `String` parameters.

### Keyset pagination

For a table large enough that offset pagination's cost (discarding every row before the requested
page) starts to matter - see [`docs/SCALING.md`](../../docs/SCALING.md) - use `queryAfter` instead
of `query`:

```java
AuditCursorQueryResponse page = client.queryAfter(AuditCursorQueryRequest.newBuilder()
        .setAuditType("PAYMENT")
        .setLimit(200)
        .build()); // cursorCreatedAt/cursorId left unset: first page

AuditRecordProto last = page.getRecords(page.getRecordsCount() - 1);
AuditCursorQueryRequest nextPage = AuditCursorQueryRequest.newBuilder()
        .setAuditType("PAYMENT")
        .setCursorCreatedAt(last.getCreatedAt())
        .setCursorId(last.getId())
        .setLimit(200)
        .build();
```

A page shorter than `limit` means you've reached the end. `AuditRecordProtos.createdAt(record)`
parses a record's raw ISO-8601 `created_at` wire string back to a `LocalDateTime`, if you need it
for anything beyond round-tripping it into the next cursor.

### Configuring timeouts, interceptors, or a custom request factory

The 2-argument constructor builds its own internal `RestClient` with Spring's defaults. For
anything beyond that - connect/read timeouts, interceptors, a custom
`ClientHttpRequestFactory` - use the 3-argument constructor, which takes a `RestClient.Builder` to
start from (the base URL and Protobuf message converter are still applied on top of it):

```java
AuditLogHttpClient client = new AuditLogHttpClient(
        RestClient.builder().requestFactory(myRequestFactory),
        "https://audit.example.com",
        "<tenant-api-key>");
```

## Error handling

Every method throws a typed subtype of `AuditLogClientException` instead of letting `RestClient`'s
generic `RestClientResponseException`/`ResourceAccessException` hierarchy propagate uncaught:

| Exception | When |
|---|---|
| `AuditLogClientAuthenticationException` | The configured API key is missing or doesn't match any configured tenant (`401`) |
| `AuditLogClientBadRequestException` | The server rejected the request itself - a malformed cursor, a mismatched `tenant_id`, an oversized page size (`400`) |
| `AuditLogClientServerException` | The server responded with a `5xx` |
| `AuditLogClientConnectionException` | The request never reached the server at all |

```java
try {
    client.ingest(request);
} catch (AuditLogClientAuthenticationException e) {
    // the configured API key is wrong
} catch (AuditLogClientException e) {
    // anything else - bad request, server error, connection failure
}
```

## Other languages

Not a Java-only wire format: `audit-log-server-proto`'s `audit_event.proto` is the source of truth,
and [`docs/CLIENT_CODEGEN.md`](../../docs/CLIENT_CODEGEN.md) at the repository root covers
generating a client directly from it in any language `protoc` supports.
