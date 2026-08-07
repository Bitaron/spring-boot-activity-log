# audit-log-spring-boot-server

Optional REST ingestion/query server for `audit-log` - for a caller with no in-process JVM method
invocation to intercept via `@Audit`/AOP (a remote service, a non-JVM caller). A pure library, no
main class or `spring-boot-maven-plugin` - see
[`audit_log_standalone_server`](../../audit_log_standalone_server) for a runnable deployment of it.

Off by default (`audit.log.server.enabled=false`) - depending on this module must not silently
expose HTTP endpoints.

## Endpoints

- `POST /audit-log/events` - records one fully-described event via `AuditLogRecorder`. Returns
  `202 Accepted` once handed to the configured delivery pipeline, not once durably committed.
- `GET /audit-log/records` - `actorId`/`auditType`/`createdAtFrom`/`createdAtTo`/`page`/`size`
  query parameters, same semantics as `AuditLogQueryService.find` (including the
  `audit.log.query.max-page-size` cap).

Both accept/return `application/x-protobuf` or, for debugging/curl convenience,
`application/json` (Protobuf's canonical JSON mapping).

## Multi-tenancy: authenticated, not just tagged

Unlike the core starter's own default (a header a caller could set to anything), this module
authenticates which tenant a request acts as: every request must present, via the `X-API-Key`
header, one of the secrets configured under `audit.log.server.api-keys.<tenantId>` - a key
identifies exactly one tenant, and a caller holding it can never act as any other tenant, no
matter what it puts in a header or request body.

```properties
audit.log.multi-tenancy.enabled=true
audit.log.server.enabled=true
audit.log.server.api-keys.acme-corp=<secret-for-acme>
audit.log.server.api-keys.globex-inc=<secret-for-globex>
```

Requires `audit.log.multi-tenancy.enabled=true` (the core starter's own flag) whenever this module
is enabled - fails startup otherwise. Per-tenant keys authenticate a tenant identity; it's the core
flag that makes `JpaAuditLogQueryService` actually confine every read to it. Without both,
authenticating a tenant would accomplish nothing.

- **Ingest**: the persisted event's tenant is always the one authenticated by the API key -
  `AuditIngestController` rejects (`400`) a request body whose `tenant_id` names a *different*
  tenant than the one authenticated, rather than silently overriding it. Simplest usage: don't set
  `tenant_id` on the wire at all; it's derived from the key.
- **Query**: `GET /audit-log/records` has no `tenant_id` query parameter at all (see
  `audit_event.proto`'s `AuditQueryRequest` comment) - reads are scoped entirely by
  `ApiKeyAuditTenantResolver`, the `AuditTenantResolver` this module registers ahead of the core
  starter's own header-based default (see `AuditLogServerAutoConfiguration`'s
  `@AutoConfigureBefore`).

Still a first cut, not a complete auth solution - a static key per tenant has no rotation or
revocation story of its own. Front this module with real authn/authz (mTLS, an OAuth2 resource
server, network policy) before exposing it beyond a trusted network - see
`AuditLogServerProperties.apiKeys`'s javadoc.

## Client options

- **Java**: [`audit-log-java-client`](../audit-log-java-client)'s `AuditLogHttpClient` - a thin
  `RestClient` wrapper. Construct it with whichever tenant's API key you're calling as; which
  tenant a call acts as follows from that key alone.
- **Any other language**: generate a client directly from `audit_event.proto` in
  [`audit-log-server-proto`](../audit-log-server-proto) - see
  [`docs/CLIENT_CODEGEN.md`](../../docs/CLIENT_CODEGEN.md) at the repository root.

## Configuration (`audit.log.server.*`)

| Property | Default | Description |
|---|---|---|
| `audit.log.server.enabled` | `false` | Master switch. |
| `audit.log.server.api-keys.<tenantId>` | - | Per-tenant API key; at least one required when enabled. |

See the root [`AGENTS.md`](../../AGENTS.md) for the full `audit.log.*`/`audit.log.server.*`
reference across both modules.
