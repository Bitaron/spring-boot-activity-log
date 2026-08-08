# audit-log-spring-boot-grpc-server

Optional gRPC ingestion/query server for `audit-log` (WP18) - the same three operations as
[`audit-log-spring-boot-server`](../audit-log-spring-boot-server)'s REST endpoints, on the
`AuditLogService` gRPC service defined in `audit_event.proto`, for callers that prefer gRPC over
REST/HTTP. A pure library, no main class or `spring-boot-maven-plugin`.

Off by default (`audit.log.grpc.enabled=false`) - depending on this module must not silently open
a network port.

**Cannot be enabled in the same application as `audit-log-spring-boot-server`** - see
[Mutual exclusion with the REST server module](#mutual-exclusion-with-the-rest-server-module)
below. Run REST and gRPC as separate deployed instances if you need both.

## RPCs

`AuditLogService`, defined in
[`audit_event.proto`](../audit-log-server-proto/src/main/proto/auditlog/v1/audit_event.proto):

- `Ingest(AuditEventRequest) returns (AuditEventResponse)` - mirrors `POST /audit-log/events`:
  records one fully-described event via `AuditLogRecorder`. The response's `accepted` field means
  the request was handed to the configured delivery pipeline, not that it's durably committed yet.
- `Query(AuditQueryRequest) returns (AuditQueryResponse)` - mirrors `GET /audit-log/records`, same
  semantics as `AuditLogQueryService.find` (including the `audit.log.query.max-page-size` cap).
- `QueryAfter(AuditCursorQueryRequest) returns (AuditCursorQueryResponse)` - mirrors
  `GET /audit-log/records/after`, same semantics as `AuditLogQueryService.findAfter`: keyset
  ("seek") pagination for once a table is large enough that offset pagination's cost starts to
  matter (see [`docs/SCALING.md`](../../docs/SCALING.md)). Omit `cursor_created_at`/`cursor_id` for
  the first page; otherwise both must be supplied together (`INVALID_ARGUMENT` if only one is) -
  the last returned record's `created_at`/`id`.

Unlike REST, a gRPC RPC always has exactly one typed request message - `AuditQueryRequest`/
`AuditCursorQueryRequest` (originally, on the REST side, just typed documentation of accepted query
parameters) are the literal wire request bodies here.

## Multi-tenancy: authenticated, not just tagged

Same authentication model as the REST server module: every call must present, via the `x-api-key`
gRPC metadata entry, one of the secrets configured under `audit.log.grpc.api-keys.<tenantId>` - a
key identifies exactly one tenant, and a caller holding it can never act as any other tenant, no
matter what it puts in a request message.

```properties
audit.log.multi-tenancy.enabled=true
audit.log.grpc.enabled=true
audit.log.grpc.port=9090
audit.log.grpc.api-keys.acme-corp=<secret-for-acme>
audit.log.grpc.api-keys.globex-inc=<secret-for-globex>
```

Requires `audit.log.multi-tenancy.enabled=true` (the core starter's own flag) whenever this module
is enabled - fails startup otherwise, for the identical reason the REST server module requires it:
per-tenant keys authenticate a tenant identity; it's the core flag that makes
`JpaAuditLogQueryService` actually confine every read to it.

- **Ingest**: the persisted event's tenant is always the one authenticated by the API key -
  `AuditLogGrpcService.ingest` rejects (`INVALID_ARGUMENT`) a request whose `tenant_id` names a
  *different* tenant than the one authenticated, rather than silently overriding it. Simplest
  usage: don't set `tenant_id` on the wire at all; it's derived from the key.
- **Query**: neither `AuditQueryRequest` nor `AuditCursorQueryRequest` has a `tenant_id` field at
  all (see `audit_event.proto`'s comments on those messages) - reads are scoped entirely by
  `GrpcAuditTenantResolver`, the `AuditTenantResolver` this module registers ahead of the core
  starter's own header-based default (see `AuditLogGrpcServerAutoConfiguration`'s
  `@AutoConfigureBefore`).

Still a first cut, not a complete auth solution - a static key per tenant has no rotation or
revocation story of its own, and `x-api-key` metadata travels in cleartext unless the channel
itself is encrypted. Front this module with TLS and/or real network policy (mTLS, a service mesh,
network policy) before exposing it beyond a trusted network - see
`AuditLogGrpcServerProperties.apiKeys`'s javadoc.

## Mutual exclusion with the REST server module

`audit-log-spring-boot-server` and this module authenticate tenants into two different,
non-interoperable request-scoped contexts: an `HttpServletRequest` attribute for REST, a gRPC
`Context` value here. Only one `AuditTenantResolver` bean can be active application-wide, so
enabling both modules in the same application would leave whichever one's autoconfiguration
processes second silently unable to resolve a tenant. Rather than leave that as a subtle,
order-dependent latent bug, each module's autoconfiguration fails startup loudly if the other's
`enabled` property is also `true` - deploy REST and gRPC as separate application instances instead
if you need both protocols.

## Client options

Generate a client directly from `audit_event.proto`'s `AuditLogService` in
[`audit-log-server-proto`](../audit-log-server-proto) with `protoc` + the `grpc-java` plugin (or
your language's gRPC plugin) - see [`docs/CLIENT_CODEGEN.md`](../../docs/CLIENT_CODEGEN.md) at the
repository root. There is no dedicated `audit-log-java-client`-style wrapper for gRPC yet; the
generated `AuditLogServiceGrpc` stub plus an interceptor attaching the `x-api-key` metadata entry
(see this module's own tests for an example, `io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor`)
is enough to call it directly.

## Configuration (`audit.log.grpc.*`)

| Property | Default | Description |
|---|---|---|
| `audit.log.grpc.enabled` | `false` | Master switch. |
| `audit.log.grpc.port` | `9090` | Port the gRPC server listens on. `0` binds an OS-assigned ephemeral port (mainly useful for tests). |
| `audit.log.grpc.api-keys.<tenantId>` | - | Per-tenant API key; at least one required when enabled. |

See the root [`AGENTS.md`](../../AGENTS.md) for the full `audit.log.*`/`audit.log.grpc.*`
reference across both modules.
