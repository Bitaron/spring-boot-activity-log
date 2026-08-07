# Migrating from 1.x to 2.x

`2.x` is a breaking redesign of the public API and schema. Only `1.0-Beta` was ever published
(February 2025), and the `main` branch had already drifted from that published API before this
redesign started - so nothing on Maven Central compiles against the pre-2.x source as it existed
in this repository anyway. This document maps every removed/renamed API to its replacement.

Run [`db/migration/V2__audit_log_v2.sql`](db/migration/V2__audit_log_v2.sql) against an existing
1.x database before deploying 2.x.

## Coordinates

| 1.x | 2.x |
|---|---|
| `io.github.bitaron:audit-log` | `io.github.bitaron:audit-log-spring-boot-starter` (depend on this) |
| (single module) | `audit-log-spring-boot-autoconfigure` (implementation; the starter depends on it - don't depend on this directly) |
| `io.github.bitaron.auditLog` (camelCase package - invalid per JLS §6.1) | `io.github.bitaron.auditlog` |
| `AuditLogSpringBootAutoConfig` | `AuditLogAutoConfiguration` |
| `io.github.bitaron.auditlog.config.spring` | `io.github.bitaron.auditlog.autoconfigure` |

## `@Audit`

| 1.x | 2.x |
|---|---|
| `templateNameList = {"x"}` | `templates = {"x"}` |
| `isActorSystem = true` | `actorSource = ActorSource.SYSTEM` |
| `isActorCommon = false` (required the method's return type to implement `AuditLogGenericDataGetter`) | `actorSource = ActorSource.EXPRESSION, actorExpression = "#result.someField"` (a SpEL expression over `#result`/`#args`/`#exception` - no coupling to the return type) |
| `isActorSystem = false, isActorCommon = true` (the implicit default) | `actorSource = ActorSource.CONTEXT` (now the explicit default) |
| Not repeatable | `@Repeatable(Audits.class)`, `@Target({METHOD, TYPE})`, `@Documented`, `@Inherited` |

`AuditLogGenericDataGetter` itself is unchanged and still used for `ActorSource.CONTEXT` -
only the `isActorCommon = false` downcast-the-return-type path was removed, replaced by
`ActorSource.EXPRESSION`.

## Context / DTO

| 1.x | 2.x |
|---|---|
| `AuditLogClientData` (mutable, public setters, constructor performed `RequestContextHolder`/header lookups) | `AuditContext` (immutable `record`: `actorId`, `actorName`, `clientLocation`, `clientIp`, `userAgent`, `args`, `result`, `exception`, `exceptionThrown`, `durationMillis`, `traceId`) |
| Actor/client resolution happened inside `AuditLogClientData`'s constructor | Moved to `AuditContextResolver` / `DefaultAuditContextResolver` - a real bean, unit-testable with `MockHttpServletRequest` and no Spring context |
| `AuditLogTemplateResolver.resolveTemplate(String, String, AuditLogClientData)` | `resolveTemplate(String, String, AuditContext)` |
| `AuditLogArgumentSerializer.serialize(Object)` | Unchanged signature, but the writer now passes only `{args, result, exception, exceptionThrown}` - not the whole context (actor/client fields are no longer duplicated into the `data` JSON on top of their own columns) |

If your own template used `${response...}`, rename it to `${result...}` - `AuditContext`'s
component is named `result`, matching the `#result` SpEL variable used in `actorExpression`.

## Data model

One `@Audit` invocation is one `audit_log` row in both versions, but what "one row" contains
changed:

| 1.x | 2.x |
|---|---|
| One `audit_log` row **per template** in `templateNameList` (N templates = N duplicated rows, each with the full actor/client/data payload) | One `audit_log` row **per invocation**; each rendered template is a child `audit_log_message` row (`audit_log_id`, `template_name`, `message`) |
| `audit_log.template_id`, `audit_log.message` | Moved to `audit_log_message.template_name`, `audit_log_message.message` |
| No outcome/duration/trace columns | `audit_log.outcome` (`SUCCESS`/`FAILURE`), `audit_log.duration_ms`, `audit_log.trace_id` (read from MDC's `traceId` key, populated by Micrometer Tracing when present) |
| `data` serialized the entire client-data object (actor/client fields duplicated into JSON) | `data` serializes only `{args, result, exception, exceptionThrown}` |
| `AuditGroup.name` had no uniqueness constraint - a new row was inserted per invocation | Unique constraint on `name`; an existing group is reused by name |
| `@AfterReturning`/`@AfterThrowing` advice (no duration capture) | Single `@Around` advice measuring duration; `AuditLogAspect` has an explicit `@Order` |

Query results now come from `AuditLogQueryService`/`AuditRecord` (see "Reading records" below),
not by joining `audit_log`/`audit_log_message` yourself - but if you do need to read the raw
tables, `audit_log_message` is the table to join for message content, not `audit_log.message`.

## Template sourcing

| 1.x | 2.x |
|---|---|
| Only the `audit_template` database table | Pluggable `AuditTemplateSource` SPI; ships `PropertiesAuditTemplateSource` (`audit.log.templates.<name>=<template>`, tried first) and `DatabaseAuditTemplateSource` (the `audit_template` table, tried second) |
| A missing template only ever logged a runtime `WARN` | Same default, plus opt-in `audit.log.fail-on-missing-template=true` to fail application startup instead |

## Configuration properties

| 1.x | 2.x |
|---|---|
| `audit.log.header-mappings.requesterId` | `audit.log.headers.requester-id` |
| `audit.log.header-mappings.requesterName` | `audit.log.headers.requester-name` |
| `AuditLogProperties.REQUESTER_ID` / `REQUESTER_NAME` constants, `getHeaderFor(String)` | Removed - use `AuditLogProperties.Headers` (`getRequesterId()`/`getRequesterName()`) directly |
| No delivery mode - always fire-and-forget, dispatched before the caller's transaction committed | `audit.log.mode` = `ASYNC` (default, now commit-aware - deferred until the caller's transaction commits) or `SYNC` (shares the caller's transaction) |
| No numeric validation | `@Min`/`@NotNull` on executor sizes, `maxSerializedDataLength`, `maxTemplateCacheSize`, `mode` - enforced only when a JSR-303 provider (e.g. `spring-boot-starter-validation`) is on your classpath |
| `audit.log.executor.await-termination-seconds` | New; default `30` - queued/running writes get this long to finish on graceful shutdown before being reported as dropped |
| No `audit.log.templates.*` / `audit.log.fail-on-missing-template` | New - see "Template sourcing" above |

## Reading audit records

1.x had no supported read API - consumers queried the `AuditLog` entity directly. 2.x adds:

```java
Page<AuditRecord> page = auditLogQueryService.find(
        new AuditQuery(actorId, auditType, from, to), pageable);
```

`AuditRecord` is an immutable projection, not the JPA entity - insulated from future schema
changes the way querying `AuditLog` directly never was.

## Correctness fixes (no API change, but behavior changed)

These aren't renames - the old behavior was a bug:

- **Commit-aware dispatch (the headline fix).** In 1.x, a business method whose transaction later
  rolled back could still leave behind an audit row describing an operation that never happened -
  dispatch fired unconditionally from `@AfterReturning`/`@AfterThrowing`, before the transaction
  had a chance to commit or roll back. In `ASYNC` mode (default), dispatch now defers to
  `afterCommit()` when a transaction is active.
- **`EntityManager` bean pollution.** 1.x registered a plain `@Bean EntityManager`, which could
  make a host application's own unqualified `@Autowired EntityManager` silently resolve the
  starter's internal instance instead of failing loudly, and could introduce an ambiguity in a
  multi-persistence-unit host. 2.x never registers an `EntityManager`-typed bean at all.
- **Unbounded template cache.** The compiled-FreeMarker-template cache is now bounded
  (`audit.log.max-template-cache-size`, LRU-evicted) instead of growing forever as templates are
  edited.
- **Silent delivery loss.** Executor rejection, write failures, and writes still queued at
  shutdown are now counted via the `audit.log.records{outcome=...}` Micrometer counter (when
  Micrometer is present), not just logged at `WARN`.

## New since the initial 2.0.0-SNAPSHOT (still 2.x - additive, nothing below is a breaking change)

All of the following is new; nothing existing was renamed or removed to add it. See `HANDOFF.md`
for the full rationale behind each and `docs/SCALING.md`/`docs/CLIENT_CODEGEN.md` for the two new
docs.

| Area | What's new |
|---|---|
| `@Audit` | `mode()` attribute (`AuditDeliveryMode`: `INHERIT`/`ASYNC`/`SYNC`) overrides `audit.log.mode` per call site. Default `INHERIT` - existing usages are unaffected. |
| `@Audit` | Stacking two or more `@Audit` on one method now fires **every** instance, not just the first - a previously-documented limitation (`Audits`' javadoc), now fixed. |
| Startup | New `AuditSchemaValidator`: fails startup with an actionable message if `audit_log`/`audit_log_message`/`audit_template`/`audit_group` are missing. On by default; `audit.log.schema-validation.enabled=false` to skip. |
| Reads | `AuditLogQueryService.find` now honors `Pageable.getSort()` (whitelisted to `id`/`createdAt`/`actorId`/`auditType`) and rejects page sizes above `audit.log.query.max-page-size` (default `200`) instead of scanning unbounded. |
| Reads | New `AuditLogQueryService.findAfter(query, cursor, limit)` + `AuditCursor` - keyset/seek pagination for large tables, independent of how deep into the result set you are. |
| Writes | New opt-in `AuditLogRetentionService`: scheduled, batched deletion of rows older than `audit.log.retention.max-age`. Off by default (`audit.log.retention.enabled=false`). |
| Writes | New `AuditLogRecorder` (+ `AuditEventRequest`): record an audit event programmatically, with no `@Audit`-annotated method invocation for AOP to intercept - a message-queue consumer, a batch job, or the new REST server module below. |
| Server | New optional module `audit-log-spring-boot-server`: a Protobuf-over-HTTP ingestion/query server (`POST /audit-log/events`, `GET /audit-log/records`). Off by default (`audit.log.server.enabled=false`); requires `audit.log.server.api-key` once enabled. |
| Client | New `audit-log-server-proto` (generated Protobuf types, `.proto` schema bundled in the jar) and `audit-log-java-client` (a typed `RestClient` wrapper) modules for talking to the server module. |
| Multi-tenancy | New opt-in tenant tagging/scoping (`audit.log.multi-tenancy.enabled=false` by default - zero behavior change on upgrade for the core starter's own reads/writes). New `AuditTenantResolver` SPI (`DefaultAuditTenantResolver` reads `audit.log.headers.tenant-id`, default `X-TENANT-ID`); `AuditContext`/`AuditEventRequest`/`AuditRecord` each gain a trailing `tenantId` field (source-breaking for any direct positional-constructor call - update call sites); new nullable `audit_log.tenant_id` column (`V3__audit_log_multi_tenancy.sql`). Once enabled, every `AuditLogQueryService` read is unconditionally scoped to the resolved tenant (not a caller-suppliable `AuditQuery` field) and fails closed (`IllegalStateException`) if none resolves. `AuditEventRequest`/`AuditRecordProto` gain a `tenant_id` field on the wire; `AuditQueryRequest` deliberately does not, for the reason documented in `audit_event.proto`. |
| Multi-tenancy (WP16) | `AuditTemplate`/`AuditGroup` are now tenant-scoped too (new `tenant_id` column, `""` sentinel for "global" - see `AuditTemplate.GLOBAL_TENANT_ID`; `V4__audit_log_tenant_scoped_templates_groups.sql`); new `audit.log.tenant-templates.<tenantId>.<name>` property layer. `AuditLogRetentionService` purges per-tenant, honoring a new `audit.log.retention.tenant-max-age.<tenantId>` override with `retention.max-age` as the fallback. The server module's authentication is now per-tenant - see "Breaking changes" below, this one is **not** purely additive. |
| API ergonomics (WP17) | Purely additive - no breaking changes. `AuditEventRequest.builder(auditType)` (+ `success`/`failure` convenience methods); `AuditQuery.byActor`/`byType`/`byActorAndType` static factories and `withActor`/`withType`/`withCreatedBetween` withers; `AuditRecord.toCursor()`; `AuditLogGenericDataGetter`'s 5 methods are now `default` (existing implementers compile unchanged). New published `testsupport.AuditLogAssertions` (the core module's `tests` classifier) for asserting audit records through `AuditLogQueryService` in a consumer's own tests. |
| Client/server (WP17) | New `GET /audit-log/records/after` server endpoint + `AuditLogQueryService#findAfter`-equivalent `AuditLogHttpClient#queryAfter`, backed by new `AuditCursorQueryRequest`/`AuditCursorQueryResponse` proto messages. `AuditLogHttpClient` gained a `RestClient.Builder`-accepting constructor, `query(AuditQueryRequest)`, and a typed `client.exception.AuditLogClientException` hierarchy (`Authentication`/`BadRequest`/`Server`/`Connection`) instead of letting `RestClient`'s generic exceptions propagate. New optional module `audit-log-java-client-spring-boot-starter` auto-registers an `AuditLogHttpClient` bean from `audit.log.client.*` properties (off by default). Also fixes a real pre-existing bug: `AuditServerExceptionHandler` was never registered as a `@Bean`, so a caller sending only `Accept: application/x-protobuf` to a caller-error request got an uncaught `500` instead of the documented `400` in any real host application - see "Decisions" #17 in `HANDOFF.md`. |
| gRPC server (WP18) | New optional module `audit-log-spring-boot-grpc-server`: a gRPC ingestion/query server implementing the same three operations as the REST server module (`Ingest`/`Query`/`QueryAfter`), on a new `service AuditLogService` block added to `audit_event.proto` (reusing every existing message unchanged - no new message types). Off by default (`audit.log.grpc.enabled=false`); requires `audit.log.multi-tenancy.enabled=true`; per-tenant `audit.log.grpc.api-keys.<tenantId>` authentication via the `x-api-key` gRPC metadata entry, same model as the REST server module's `X-API-Key` header. **Cannot be enabled in the same application as `audit-log-spring-boot-server`** - both fail startup if the other's `enabled` property is also `true` (see "Decisions" #18 in `HANDOFF.md`); run them as separate deployed instances. Purely additive - no existing API, message, or property was changed to add it. |

### Breaking changes in WP16 (server module + `AuditTemplateSource` SPI)

Unlike everything else in this table, these two are not backward compatible - there was no
existing deployment of this pre-release library to preserve compatibility for, so the redesign
went straight to the better shape rather than keeping both old and new side by side:

- **`audit.log.server.api-key` (single shared secret) is gone.** Replaced entirely by
  `audit.log.server.api-keys.<tenantId>` (a map, at least one entry required) - see the server
  module's own README. `audit.log.server.multi-tenancy.required` is also gone: every ingest is now
  authenticated to exactly one tenant by the key it's presented with, so there's no separate
  "should tenant be required" toggle left to have. The server module additionally now requires
  `audit.log.multi-tenancy.enabled=true` whenever it's enabled - fails startup otherwise.
- **`AuditTemplateSource.findTemplate(String name)` is now `findTemplate(String tenantId, String name)`.**
  Any custom `AuditTemplateSource` bean needs its `findTemplate` signature updated; a source with no
  notion of tenancy can simply ignore the new parameter.

### New configuration properties

| Property | Default | Purpose |
|---|---|---|
| `audit.log.schema-validation.enabled` | `true` | Startup table-existence check |
| `audit.log.query.max-page-size` | `200` | Max page size `find`/`findAfter` accept |
| `audit.log.retention.enabled` | `false` | Master switch for scheduled purge |
| `audit.log.retention.max-age` | *(required if enabled)* | Records older than this become eligible for deletion |
| `audit.log.retention.cron` | `0 0 3 * * *` | When the purge job runs |
| `audit.log.retention.batch-size` | `1000` | Rows deleted per batch iteration |
| `audit.log.server.enabled` | `false` | Master switch for the REST server module; requires `audit.log.multi-tenancy.enabled=true` (WP16) |
| `audit.log.server.api-keys.<tenantId>` | *(at least one required if enabled)* | Per-tenant API key (WP16) via the `X-API-Key` header - replaces the removed `audit.log.server.api-key` |
| `audit.log.multi-tenancy.enabled` | `false` | Master switch for tenant tagging/scoping |
| `audit.log.headers.tenant-id` | `X-TENANT-ID` | Header the default `AuditTenantResolver` reads from |
| `audit.log.tenant-templates.<tenantId>.<name>` | - | Per-tenant template override (WP16) |
| `audit.log.retention.tenant-max-age.<tenantId>` | - | Per-tenant retention window override (WP16) |
| `audit.log.client.enabled` | `false` | Master switch for the auto-registered `AuditLogHttpClient` bean (WP17) |
| `audit.log.client.base-url` | *(required if enabled)* | The REST server module's base URL |
| `audit.log.client.api-key` | - | Which tenant this client acts as, per `audit.log.server.api-keys.<tenantId>` on the server |
| `audit.log.client.http.connect-timeout` / `read-timeout` | `5s` / `30s` | Timeouts for every request this client makes |
| `audit.log.grpc.enabled` | `false` | Master switch for the gRPC server module (WP18); requires `audit.log.multi-tenancy.enabled=true` and cannot coexist with `audit.log.server.enabled=true` |
| `audit.log.grpc.port` | `9090` | Port the gRPC server listens on; `0` binds an OS-assigned ephemeral port |
| `audit.log.grpc.api-keys.<tenantId>` | *(at least one required if enabled)* | Per-tenant API key (WP18) via the `x-api-key` gRPC metadata entry - own namespace, separate from `audit.log.server.api-keys.<tenantId>` |
