# audit-log

A Spring Boot starter that records audit trail entries for annotated methods via AspectJ,
rendering messages from pluggable-source (properties or database) FreeMarker templates.

## Install

```xml
<dependency>
    <groupId>io.github.bitaron</groupId>
    <artifactId>audit-log-spring-boot-starter</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

Requires a JPA/Hibernate application (`EntityManager` on the classpath and a configured
`DataSource`). The starter ships its own entities (`AuditLog`, `AuditLogMessage`, `AuditTemplate`,
`AuditGroup`) and adds them to your application's entity scan automatically without narrowing what
your own application scans - see
[`AuditLogEntityScanRegistrar`](src/main/java/io/github/bitaron/auditlog/autoconfigure/AuditLogEntityScanRegistrar.java)
if you want to know how.

See [`MIGRATION.md`](../MIGRATION.md) if you're upgrading from `1.x`.

## Usage

```java
@Audit(auditType = "USER_MANAGEMENT", actionName = "update-profile", actionType = "UPDATE",
        templates = {"profile_updated"})
public void updateProfile(UpdateProfileRequest request) { ... }
```

Every invocation of an `@Audit`-annotated method produces exactly **one** `audit_log` row -
regardless of how many templates it names. Each template in `templates()` that resolves (via any
configured `AuditTemplateSource` - see below) renders into a child `audit_log_message` row; a
template name that resolves nowhere is skipped with a `WARN` log line (or fails startup - see
`audit.log.fail-on-missing-template`). If `templates` is empty, the row is still recorded with no
child messages. A method that throws is recorded the same way, with `outcome=FAILURE` and the
exception available to the template as `exception`; a failure to record an entry never affects the
audited method's own outcome (see "Failure isolation" below).

Exclude a parameter from the recorded arguments with `@AuditIgnore`:

```java
@Audit(auditType = "AUTH", actionName = "login", templates = {"login_attempt"})
public LoginResult login(LoginRequest request, @AuditIgnore HttpServletResponse response) { ... }
```

### Actor resolution

`@Audit(actorSource = ...)` controls how the actor is determined:

- **`CONTEXT`** (default) - the configured `AuditLogGenericDataGetter` bean, or HTTP headers if
  none is configured (see "Trust model" below).
- **`SYSTEM`** - actor id/name are always `"SYSTEM"`, for scheduled jobs and other
  non-request-driven invocations.
- **`EXPRESSION`** - a SpEL expression (`actorExpression`) evaluated against the method's result,
  arguments, and exception:

  ```java
  @Audit(auditType = "ORDER", templates = {"order_created"},
          actorSource = ActorSource.EXPRESSION, actorExpression = "#result.ownerId")
  public Order createOrder(OrderRequest request) { ... }
  ```

  This replaces the `1.x` design where the method's return type had to implement
  `AuditLogGenericDataGetter` for the aspect to downcast it.

### Delivery mode

`audit.log.mode` controls when/how the audit row is written:

- **`ASYNC`** (default) - dispatched off the caller's thread. If the audited method runs inside a
  transaction, the write is deferred until that transaction commits, so a rolled-back business
  operation never leaves behind an audit record describing something that didn't happen.
- **`SYNC`** - written on the caller's thread, sharing the caller's transaction (commits/rolls back
  atomically with it). Higher latency, but the strongest delivery guarantee this library offers.

Every place a record can be lost (executor queue full, write failure, records still queued at
shutdown) increments an `audit.log.records{outcome=...}` Micrometer counter (when Micrometer is on
the classpath) in addition to being logged - a compliance artifact needs an observable, alertable
signal for loss, not just a `WARN` line.

**Per-call override:** `@Audit(mode = ...)` overrides `audit.log.mode` for one call site, using the
same `ASYNC`/`SYNC` semantics above:

```java
@Audit(auditType = "AUTH", actionName = "login", templates = {"login_attempt"},
        mode = AuditDeliveryMode.SYNC)
public LoginResult login(LoginRequest request) { ... }
```

Defaults to `AuditDeliveryMode.INHERIT` - following the global `audit.log.mode` - so adding this
attribute to an existing `@Audit` usage changes nothing until you set it explicitly.

### Startup schema validation

On startup, this starter checks that `audit_log`, `audit_log_message`, `audit_template`, and
`audit_group` all exist in the configured database, failing fast with a message naming exactly
which table(s) are missing and pointing at `db/migration/V2__audit_log_v2.sql` - instead of the
first sign of trouble being a runtime `SQLException` buried in a `WARN` log line on the first
audited call. On by default; set `audit.log.schema-validation.enabled=false` to skip it (e.g. for a
deployment whose schema is already validated some other way, or that relies on
`spring.jpa.hibernate.ddl-auto=create`/`update`, which this check correctly waits for before
running).

## Configuration (`audit.log.*`)

| Property | Default | Description |
|---|---|---|
| `audit.log.enabled` | `true` | Master switch; disables the aspect, executor, and entity scan entirely. |
| `audit.log.mode` | `ASYNC` | `ASYNC` or `SYNC` delivery - see "Delivery mode" above. |
| `audit.log.headers.requester-id` | `X-USER-ID` | Header read for the actor id when no `AuditLogGenericDataGetter` bean is configured. |
| `audit.log.headers.requester-name` | `X-USER-NAME` | Same, for the actor name. |
| `audit.log.trust-forwarded-headers` | `false` | Whether to trust `X-Forwarded-For`/`Proxy-Client-IP`/`WL-Proxy-Client-IP` for the client IP. |
| `audit.log.masked-fields` | `password, secret, token, authorization, creditCardNumber` | Field names redacted (at any depth) in the persisted `data` JSON. |
| `audit.log.max-serialized-data-length` | `8192` | Characters after which the serialized `data` payload is truncated into a `{truncated, preview}` envelope. |
| `audit.log.max-template-cache-size` | `256` | Max compiled FreeMarker templates kept in memory (LRU-evicted beyond this). |
| `audit.log.templates.<name>` | - | Define a template in configuration instead of the `audit_template` table - see "Template sourcing" below. |
| `audit.log.fail-on-missing-template` | `false` | Fail application startup if any `@Audit(templates=...)` name can't be resolved by any configured source, instead of only warning per call. |
| `audit.log.executor.core-pool-size` / `max-pool-size` / `queue-capacity` | `2` / `10` / `500` | Sizing for the dedicated executor `ASYNC` writes are dispatched to. |
| `audit.log.executor.await-termination-seconds` | `30` | How long to wait for queued/running writes to finish on graceful shutdown before giving up on the rest. |
| `audit.log.schema-validation.enabled` | `true` | Fail startup if the 4 required tables are missing - see "Startup schema validation" above. |
| `audit.log.query.max-page-size` | `200` | Max page size `AuditLogQueryService.find`/`findAfter` accept before rejecting the request. |
| `audit.log.retention.enabled` | `false` | Master switch for the scheduled purge job - see "Retention" above. |
| `audit.log.retention.max-age` | - (required if enabled) | Records older than this become eligible for deletion. |
| `audit.log.retention.cron` | `0 0 3 * * *` | Cron schedule (six-field, seconds first) the purge job runs on. |
| `audit.log.retention.batch-size` | `1000` | Rows deleted per batch iteration. |
| `audit.log.multi-tenancy.enabled` | `false` | Master switch for tenant tagging/scoping - see "Multi-tenancy" below. |
| `audit.log.headers.tenant-id` | `X-TENANT-ID` | Header the default `AuditTenantResolver` reads from, when `multi-tenancy.enabled=true`. |
| `audit.log.tenant-templates.<tenantId>.<name>` | - | Per-tenant template override, tried before `templates.<name>` for that tenant - see "Tenant-scoped templates and groups". |
| `audit.log.retention.tenant-max-age.<tenantId>` | - | Per-tenant retention window override; falls back to `retention.max-age` for any tenant without one. |

All numeric properties are validated (`@Min`) when a JSR-303 provider (e.g.
`spring-boot-starter-validation`) is on your application's classpath; without one, invalid values
bind without error, same as if the constraints weren't declared.

### Template sourcing

Templates are resolved by trying every configured `AuditTemplateSource` bean in order:

1. **`PropertiesAuditTemplateSource`** - `audit.log.templates.<name>=<template>`, so a template can
   be versioned alongside application code instead of requiring a database write.
2. **`DatabaseAuditTemplateSource`** - the `audit_template` table (seed it yourself; the starter
   does not ship one).

A property-defined template overrides a same-named database row. Supply your own
`AuditTemplateSource` bean to add another source (e.g. loading from classpath resources).

## Reading audit records

Don't query the `AuditLog`/`AuditLogMessage` entities directly - use `AuditLogQueryService`:

```java
Page<AuditRecord> page = auditLogQueryService.find(
        new AuditQuery("actor-123", "USER_MANAGEMENT", from, to),
        PageRequest.of(0, 20));
```

`AuditQuery` filters on `actorId`, `auditType`, and a `createdAt` range - the table's three indexed
columns. `AuditRecord` is an immutable projection, not the JPA entity, so the persistence model is
free to change without breaking this API.

`Pageable`'s page size is rejected (`IllegalArgumentException`) above `audit.log.query.max-page-size`
(default `200`) rather than silently clamped, and its `Sort` is honored but restricted to a
whitelist of indexed properties (`id`, `createdAt`, `actorId`, `auditType`) - both to keep every
accepted query index-backed.

**At scale**, prefer keyset ("seek") pagination over `find`'s offset pagination - its cost doesn't
grow with how deep into the result set you are, unlike `OFFSET`/`LIMIT`:

```java
List<AuditRecord> page = auditLogQueryService.findAfter(AuditQuery.all(), cursor, 200);
AuditRecord last = page.get(page.size() - 1);
AuditCursor nextCursor = new AuditCursor(last.createdAt(), last.id()); // pass into the next call
```

Start with `cursor = null` for the first page; a page shorter than the requested limit means
you've reached the end. See [`docs/SCALING.md`](../../docs/SCALING.md) for when this matters and
why.

## Retention

`AuditLogRetentionService` deletes `AuditLog`/`AuditLogMessage` rows older than a configured age,
in bounded batches, on its own dedicated scheduler - **off by default**, since deleting audit
history is a decision this starter must never make for you unasked:

```properties
audit.log.retention.enabled=true
audit.log.retention.max-age=P90D
audit.log.retention.cron=0 0 3 * * *
audit.log.retention.batch-size=1000
```

`max-age` is required once `enabled=true` - there's no safe default retention window for a
compliance artifact. See [`docs/SCALING.md`](../../docs/SCALING.md) for how this composes with
table partitioning on a very large table.

**Per-tenant overrides**: purging runs once per distinct tenant present in `audit_log` (including
the no-tenant/legacy case), each against its own effective cutoff:

```properties
audit.log.retention.tenant-max-age.acme-corp=P30D
```

A tenant with a shorter (or no) override is never purged by another tenant's window - `max-age`
remains the default for any tenant with no entry here, and for legacy/no-tenant rows regardless of
what overrides exist.

## Recording events without `@Audit`

`@Audit` + AOP only works for an in-process method call. For anything else with event data to
record but no method invocation to intercept - a message-queue consumer, a batch job, the REST
server module below - use `AuditLogRecorder` directly:

```java
auditLogRecorder.record(AuditEventRequest.builder("PAYMENT")
        .actionName("capture")
        .actionType("UPDATE")
        .templates(List.of("payment_captured"))
        .actorId(actorId)
        .actorName(actorName)
        .clientIp(clientIp)
        .clientLocation(clientLocation)
        .userAgent(userAgent)
        .args(args)
        .success(result)     // or .failure(exception) - each also sets exceptionThrown
        .durationMillis(durationMillis)
        .traceId(traceId)
        .tenantId(tenantId)
        .build());
```

The builder is preferred over the canonical (positional, 17-argument) constructor for anything
beyond a trivial call - several adjacent `String` fields (`actorId`/`actorName`,
`clientIp`/`clientLocation`) are easy to transpose without the compiler noticing.

It follows the same delivery pipeline (mode, commit-aware dispatch, metrics, failure isolation) as
the `@Audit` path - this is an alternate way to get an event *in*, not a different way it's
written afterward.

## Multi-tenancy

Off by default (`audit.log.multi-tenancy.enabled=false`) - with no flag flip, upgrading to this
version changes nothing: `tenant_id` stays `null` on every row and no read is ever tenant-filtered,
exactly like before this feature existed.

Turn it on and every audit record is tagged with a tenant, resolved by `AuditTenantResolver` -
consulted unconditionally on every `@Audit` invocation (tenant identity is orthogonal to actor
identity: a `SYSTEM`-actor scheduled job still runs on behalf of one tenant) and on every read
through `AuditLogQueryService`:

```properties
audit.log.multi-tenancy.enabled=true
audit.log.headers.tenant-id=X-TENANT-ID
```

With no `AuditTenantResolver` bean of your own, the default reads the configured header (see
"Trust model" below - same spoofability caveat as the actor-header defaults). For a verified tenant
identity, supply your own bean instead, e.g. backed by a claim on the authenticated principal:

```java
@Bean
AuditTenantResolver auditTenantResolver() {
    return () -> SecurityContextHolder.getContext().getAuthentication() /* ... */;
}
```

**Reads are scoped automatically, not by an `AuditQuery` field** - `AuditQuery` has no `tenantId`
parameter to remember to pass. Once enabled, `AuditLogQueryService.find`/`findAfter` resolve the
current tenant themselves and unconditionally scope every query to it, **failing closed**
(`IllegalStateException`) if none resolves, rather than ever running an unscoped, all-tenants
query. This is what makes it structurally hard for a future read to accidentally leak across
tenants - there is no per-call filter to forget.

### Tenant-scoped templates and groups

`AuditTemplate`/`AuditGroup` are tenant-scoped too: a tenant-tagged `audit_template`/`audit_group`
row (or a `audit.log.tenant-templates.<tenantId>.<name>` property) is preferred over a same-named
global one for that tenant, with the global row/property as the fallback for a tenant with no
override of its own:

```properties
# Config-defined: tried before the database, same as the tenant-agnostic layer.
audit.log.templates.login-attempt=Login by ${actorName!"unknown"}
audit.log.tenant-templates.acme-corp.login-attempt=Connexion de ${actorName!"unknown"}
```

Both tables use `""` (empty string), never `null`, as the "not tenant-specific" sentinel - unlike
`audit_log.tenant_id`'s "`null` = default tenant" convention - because both have a real composite
`(tenant_id, name)` unique constraint, and standard SQL treats every `NULL` as distinct for
uniqueness purposes; a `NULL`-based convention here would silently allow duplicate global names.
See `AuditTemplate`/`AuditGroup`'s `GLOBAL_TENANT_ID` javadoc. One consequence: the same
`@Audit(groupName = ...)` value used by two different tenants resolves to two separate `AuditGroup`
rows, never a shared one.

`AuditTemplateValidator` (the opt-in `audit.log.fail-on-missing-template=true` startup check) only
validates the global layer - it runs statically, with no per-tenant context and no way to enumerate
every tenant that will ever call an audited method, so a template that's only defined per-tenant is
reported missing there even though it resolves correctly at call time.

### Server-mode reads and per-tenant authentication

The REST server module's `POST /audit-log/events` accepts an explicit `tenant_id` on the wire (see
its own README) when authenticating with the core starter's header-based default resolver -
`GET /audit-log/records` does not accept one as a query parameter, relying on the same ambient
`AuditTenantResolver` scoping described above. **The server module itself goes further**: it
replaces the header-based default entirely with per-tenant API keys, so which tenant a request acts
as is authenticated, not just data-tagged - see its own README's "Multi-tenancy" section for how
that closes the gap this section's header-based default can't on its own.

## Server mode and other-language clients

For a caller outside this JVM entirely, the optional `audit-log-spring-boot-server` module exposes
`AuditLogRecorder`/`AuditLogQueryService` over HTTP (Protobuf wire format, JSON also supported for
debugging), gated by `audit.log.server.enabled` (off by default) and an `X-API-Key` header. Java
callers can use `audit-log-java-client`; any other language can generate a client directly from the
`.proto` schema in `audit-log-server-proto` - see
[`docs/CLIENT_CODEGEN.md`](../../docs/CLIENT_CODEGEN.md).

## Extension points

- **`AuditLogGenericDataGetter`** - supply your own actor/client resolution (e.g. from
  `SecurityContextHolder`, a JWT claim, a non-HTTP context). If Spring Security is on the
  classpath and you don't provide one, a `SecurityContextHolder`-backed default is registered
  automatically - see "Trust model" below.
- **`AuditLogTemplateResolver`** - swap out FreeMarker for your own template engine.
- **`AuditLogArgumentSerializer`** - swap out the default Jackson-based serializer.
- **`AuditLogLocationResolver`** - plug in IP geolocation (e.g. MaxMind GeoIP2); none is bundled.
- **`AuditTemplateSource`** - add another place templates can come from.
- **`AuditTenantResolver`** - supply your own tenant resolution for multi-tenancy - see
  "Multi-tenancy" above.

## Trust model

With no `AuditLogGenericDataGetter` configured, the actor identity is read from client-supplied
HTTP headers (`X-USER-ID`/`X-USER-NAME` by default) - **these are spoofable by the caller** unless
a trusted reverse proxy strips/overwrites them before the request reaches your application. The
client IP is similarly only read from `X-Forwarded-For`-style headers when
`audit.log.trust-forwarded-headers=true` is explicitly set, since those headers are equally
spoofable without a trusted proxy in front. For a verified actor identity, supply an
`AuditLogGenericDataGetter` backed by your real authentication mechanism, or rely on the
Spring-Security-backed default described above. The default `AuditTenantResolver` (see
"Multi-tenancy" above) carries the identical caveat for the `X-TENANT-ID` header when
`audit.log.multi-tenancy.enabled=true`.

FreeMarker templates are executed against the invocation's context. The resolver disables the
`?api` built-in and uses `SAFER_RESOLVER` to block reflective escapes into arbitrary classes, but
write access to wherever your templates come from is still effectively the ability to execute
template logic in this process - restrict who can edit `audit.log.templates.*` / the
`audit_template` table the same way you would restrict deploy access.

## Failure isolation

A failure anywhere in the audit pipeline - a malformed template, an unserializable argument, a
database error - is caught, logged at `WARN`, and counted via the `audit.log.records` metric. It
is designed to never surface to the audited method's caller.

## Schema

See the entity classes in `io.github.bitaron.auditlog.entity` for the mapped columns:
`audit_log` (one row per invocation; `outcome`, `duration_ms`, `trace_id`, and `data` - the
serialized `{args, result, exception, exceptionThrown}`, deliberately not duplicating the
actor/client columns), `audit_log_message` (rendered messages, keyed by `template_name`),
`audit_template`, and `audit_group`. Use `ddl-auto` for local development only; for production,
manage the schema with your own migration tool - see [`MIGRATION.md`](../MIGRATION.md) for the
`1.x` → `2.x` schema diff.
