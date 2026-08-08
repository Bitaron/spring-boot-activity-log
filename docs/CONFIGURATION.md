# Configuration reference

Every `audit.log.*` property this project defines, in one place, grouped by which module binds it.
This is the canonical reference; `AGENTS.md`/`MIGRATION.md` link here instead of repeating it in
full. Source of truth for each table is the corresponding `@ConfigurationProperties` class -
`AuditLogProperties`, `AuditLogServerProperties`, `AuditLogGrpcServerProperties`,
`AuditLogClientProperties` - so if this document and that class's javadoc ever disagree, the class
is right and this page has drifted (please file an issue or fix it in the same PR that changed the
class).

Every properties class is `@Validated` (JSR-303). Constraints (`@Min`, `@NotNull`, etc.) are only
actually *enforced* when a validator implementation - typically `spring-boot-starter-validation` -
is present on the consuming application's classpath; without one, Spring Boot binds properties
without validating them, silently, same as if the annotations weren't there (see
`AGENTS.md`'s "Conventions" #2 for why the validation API itself is `provided` scope rather than a
transitive dependency).

## Core starter (`audit.log.*`)

Bound by `AuditLogProperties` in `audit-log-spring-boot-autoconfigure`. Always active whenever the
starter is on the classpath and `audit.log.enabled=true` (the default).

| Property | Type | Default | Description |
|---|---|---|---|
| `audit.log.enabled` | `boolean` | `true` | Master switch. `false` skips registering the aspect, the executor, and the JPA entity scan entirely. |
| `audit.log.headers.requester-id` | `String` | `X-USER-ID` | Header the default actor resolution reads the actor id from. |
| `audit.log.headers.requester-name` | `String` | `X-USER-NAME` | Header the default actor resolution reads the actor display name from. |
| `audit.log.headers.tenant-id` | `String` | `X-TENANT-ID` | Header `DefaultAuditTenantResolver` reads from, when active. |
| `audit.log.trust-forwarded-headers` | `boolean` | `false` | Trust `X-Forwarded-For`/`Proxy-Client-IP`/`WL-Proxy-Client-IP` for client IP resolution. Off by default - these are trivially spoofable by the caller unless a trusted reverse proxy strips/overwrites them first, which the starter has no way to verify. |
| `audit.log.masked-fields` | `Set<String>` | `password, secret, token, authorization, creditCardNumber` | Top-level field names redacted (`***`) at any depth when method arguments/results are serialized into `data`. Case-sensitive. |
| `audit.log.max-serialized-data-length` | `int` (`@Min(1)`) | `8192` | Truncation threshold, in characters, for the serialized `data` JSON. |
| `audit.log.max-template-cache-size` | `int` (`@Min(1)`) | `256` | LRU-bounded compiled-FreeMarker-template cache size. |
| `audit.log.mode` | `ASYNC`\|`SYNC` (`@NotNull`) | `ASYNC` | Global delivery mode - see "Delivery modes" below. Overridable per call site via `@Audit(mode=...)`. |
| `audit.log.templates.<name>` | `String` | - | Define a template's body in configuration instead of the `audit_template` table. Tried before the database. |
| `audit.log.tenant-templates.<tenantId>.<name>` | `String` | - | Per-tenant template override, tried before the tenant-agnostic `templates.<name>` for that tenant. Ignored entirely when no tenant is resolved. |
| `audit.log.fail-on-missing-template` | `boolean` | `false` | Fail application startup (instead of only a per-call `WARN`) if a `@Audit(templates=...)` name can't be resolved by any configured `AuditTemplateSource`. Off by default since it requires eagerly scanning every bean's methods at startup. |
| `audit.log.executor.core-pool-size` | `int` (`@Min(1)`) | `2` | Async-dispatch executor core pool size. |
| `audit.log.executor.max-pool-size` | `int` (`@Min(1)`) | `10` | Async-dispatch executor max pool size. |
| `audit.log.executor.queue-capacity` | `int` (`@Min(1)`) | `500` | Async-dispatch executor queue capacity before writes are rejected (counted via `audit.log.records{outcome=rejected}`, not silently dropped). |
| `audit.log.executor.await-termination-seconds` | `int` (`@Min(0)`) | `30` | How long graceful shutdown waits for queued/running writes before giving up on the remainder (counted via `audit.log.records{outcome=dropped_on_shutdown}`). |
| `audit.log.schema-validation.enabled` | `boolean` | `true` | Startup check that `audit_log`/`audit_log_message`/`audit_template`/`audit_group` all exist; fails fast with an actionable message (naming the missing tables and the migration file path) rather than failing later at the first write. |
| `audit.log.query.max-page-size` | `int` (`@Min(1)`) | `200` | Largest page size `AuditLogQueryService.find`/`findAfter` accept - a larger `Pageable`/`limit` is rejected (`IllegalArgumentException`), never silently clamped. |
| `audit.log.retention.enabled` | `boolean` | `false` | Master switch for the scheduled purge job. Off by default - deleting audit history is a decision this starter must never make unasked. |
| `audit.log.retention.max-age` | `Duration` | *(required if enabled)* | Records older than this become eligible for deletion. No default - there is no safe retention window to assume for a compliance artifact. Accepts ISO-8601 duration syntax, e.g. `P90D`. |
| `audit.log.retention.cron` | `String` (`@NotNull`) | `0 0 3 * * *` | Purge job schedule - Spring's six-field cron form (seconds first). |
| `audit.log.retention.batch-size` | `int` (`@Min(1)`) | `1000` | Rows deleted per batch iteration - purging loops in bounded batches, oldest row first, rather than one unbounded `DELETE`. |
| `audit.log.retention.tenant-max-age.<tenantId>` | `Duration` | - | Per-tenant retention window override; `retention.max-age` remains the fallback for any tenant with no entry here, and for legacy/no-tenant rows regardless of what overrides exist. |
| `audit.log.multi-tenancy.enabled` | `boolean` | `false` | Master switch for tenant tagging/scoping - see "Multi-tenancy" below. With no flag flip, upgrading changes nothing: `tenant_id` stays `null` on every row and reads are never tenant-filtered. |

### Delivery modes

- **`ASYNC`** (default) - dispatched off the caller's thread. If the audited method runs inside a
  transaction, the write is deferred until that transaction commits, so a rolled-back business
  operation never leaves behind an audit record describing something that didn't happen; with no
  active transaction, the write is dispatched immediately. Best-effort - a full executor queue or a
  process crash can still lose a record (tracked via the `audit.log.records{outcome=...}` counter
  when Micrometer is present).
- **`SYNC`** - written on the caller's thread, sharing the caller's transaction (commits/rolls back
  atomically with it). Higher latency on the audited call, but the strongest delivery guarantee
  this library offers - appropriate when "the operation happened but wasn't audited" is
  unacceptable. Overridable per call site via `@Audit(mode = AuditDeliveryMode.SYNC)`, regardless of
  the global default.

### Multi-tenancy

Off by default; when `audit.log.multi-tenancy.enabled=true`, an `AuditTenantResolver` bean is
registered (`DefaultAuditTenantResolver`, reading `audit.log.headers.tenant-id`, unless a consumer
supplies their own bean or one of the server modules below overrides it with an authenticated one)
and every read through `AuditLogQueryService` is unconditionally scoped to whatever tenant it
resolves - failing closed (`IllegalStateException`) if none resolves, rather than returning
unscoped results. See `AGENTS.md`'s "Conventions" #14 for the full rationale.

## REST server module (`audit.log.server.*`)

Bound by `AuditLogServerProperties` in `audit-log-spring-boot-server`. Only active if that module
is on the classpath *and* `audit.log.server.enabled=true` - depending on the module alone does not
expose any HTTP endpoint.

| Property | Type | Default | Description |
|---|---|---|---|
| `audit.log.server.enabled` | `boolean` | `false` | Master switch. Requires `audit.log.multi-tenancy.enabled=true` and at least one `api-keys` entry - fails startup otherwise. Cannot be `true` at the same time as `audit.log.grpc.enabled` - fails startup otherwise (see "REST/gRPC mutual exclusion" below). |
| `audit.log.server.api-keys.<tenantId>` | `Map<String,String>` | *(at least one required if enabled)* | Per-tenant API key, presented via the `X-API-Key` HTTP header. A key identifies exactly one tenant - the tenant a request acts as is authenticated from the key it presents, never from anything the request body or another header says. |

Full endpoint documentation: [`audit-log-spring-boot-server/README.md`](../audit_log/audit-log-spring-boot-server/README.md)
and the [OpenAPI spec / Swagger UI](../audit_log/audit-log-spring-boot-server/src/main/resources/static/openapi/audit-log-server-openapi.yaml).

## gRPC server module (`audit.log.grpc.*`)

Bound by `AuditLogGrpcServerProperties` in `audit-log-spring-boot-grpc-server` (WP18). Only active
if that module is on the classpath *and* `audit.log.grpc.enabled=true`. Deliberately a separate
property namespace from `audit.log.server.*`, not a shared one - the two modules are independently
optional and independently deployable.

| Property | Type | Default | Description |
|---|---|---|---|
| `audit.log.grpc.enabled` | `boolean` | `false` | Master switch. Requires `audit.log.multi-tenancy.enabled=true` and at least one `api-keys` entry - fails startup otherwise. Cannot be `true` at the same time as `audit.log.server.enabled` - fails startup otherwise. |
| `audit.log.grpc.port` | `int` | `9090` | Port `AuditLogGrpcServer` binds to. `0` binds an OS-assigned ephemeral port (used by this module's own tests; not typically meaningful in production). |
| `audit.log.grpc.api-keys.<tenantId>` | `Map<String,String>` | *(at least one required if enabled)* | Per-tenant API key, presented via the `x-api-key` gRPC metadata entry - same authentication model as the REST module's `api-keys`, own separate namespace. |

Full RPC documentation: [`audit-log-spring-boot-grpc-server/README.md`](../audit_log/audit-log-spring-boot-grpc-server/README.md).

### REST/gRPC mutual exclusion

`audit.log.server.enabled=true` and `audit.log.grpc.enabled=true` **cannot both be `true` in the
same application** - each authenticates tenants into a different, non-interoperable request-scoped
context (an `HttpServletRequest` attribute for REST, a gRPC `Context` value for gRPC), and only one
`AuditTenantResolver` bean can be active application-wide. Both modules check the other's `enabled`
property at startup and fail loudly if both are set, rather than silently letting one win. Run REST
and gRPC as separate deployed instances of the same application if you need both protocols.

## Java HTTP client auto-config (`audit.log.client.*`)

Bound by `AuditLogClientProperties` in `audit-log-java-client-spring-boot-starter` (WP17). Only
active if that module is on the classpath *and* `audit.log.client.enabled=true` - registers an
`AuditLogHttpClient` bean talking to the REST server module. No gRPC equivalent client module
exists yet (see `audit-log-spring-boot-grpc-server/README.md`'s "Client options").

| Property | Type | Default | Description |
|---|---|---|---|
| `audit.log.client.enabled` | `boolean` | `false` | Master switch. `AuditLogHttpClient` isn't a Spring Boot starter by design, so this module doesn't auto-register it unconditionally the moment it's on the classpath. |
| `audit.log.client.base-url` | `String` | *(required if enabled)* | The REST server module's base URL, e.g. `https://audit.example.com`. |
| `audit.log.client.api-key` | `String` | - | The value configured as one of `audit.log.server.api-keys.<tenantId>` on the server - determines which tenant this client acts as. |
| `audit.log.client.http.connect-timeout` | `Duration` | `5s` | Connect timeout for every request this client makes. |
| `audit.log.client.http.read-timeout` | `Duration` | `30s` | Read timeout for every request this client makes. |

## Where properties are validated, and what happens if you get one wrong

- **Missing a required property with no safe default** (`audit.log.retention.max-age` when
  retention is enabled; `audit.log.server.api-keys`/`audit.log.grpc.api-keys` when the respective
  server module is enabled; `audit.log.client.base-url` when the client is enabled) fails
  application **startup**, with a message naming exactly what's missing and why there's no default
  - never a silent fallback to an insecure or meaningless default.
- **`@Min`/`@NotNull` violations** (a negative pool size, a `null` cron string, etc.) are only
  caught if a JSR-303 validator is on the classpath - see the note at the top of this document.
  Without one, an out-of-range value binds silently and fails later, wherever it's first used.
- **A caller-supplied value that violates a runtime invariant** (an oversized `page`/`limit`, an
  unsupported sort property, a half-supplied keyset cursor) is a **per-request** `400`/
  `INVALID_ARGUMENT`, not a startup failure - these aren't configuration properties, but the
  distinction matters when debugging "why did my request fail" vs. "why won't the application
  start".

See [`AGENTS.md`](../AGENTS.md) for the module map and package/class map these properties
correspond to, and [`MIGRATION.md`](../MIGRATION.md) for which properties are new since which
work package.
