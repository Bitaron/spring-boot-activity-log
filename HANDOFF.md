# Handoff: audit-log 2.0 redesign + v3 (server mode, safety rails, scale)

Status as of the last commit on `claude/project-audit-planning-ztbevf`. Written so another agent
(or human) can pick this up cold. For the *user-facing* API mapping see
[`MIGRATION.md`](MIGRATION.md); for high-volume operation see
[`docs/SCALING.md`](docs/SCALING.md); for generating a client in another language see
[`docs/CLIENT_CODEGEN.md`](docs/CLIENT_CODEGEN.md). This document is about the state of the work
itself.

## TL;DR

Three passes are **complete**:

- **v2** (WP0-WP7): the architecture/API redesign - package rename, annotation redesign, commit-
  aware dispatch, data model fix, typed config, read API. See the "v2" sections below.
- **v3** (WP8-WP14): per-call delivery override, startup schema validation, large-data
  handling (pagination/retention/partitioning docs), a programmatic write facade, an optional
  Protobuf REST server, and client codegen support.
- **WP15 (this pass): opt-in multi-tenancy.** New `AuditTenantResolver` SPI (its own interface,
  not folded into `AuditLogGenericDataGetter` - tenant identity is orthogonal to actor identity);
  a nullable `audit_log.tenant_id` column (`V3__audit_log_multi_tenancy.sql`); mandatory,
  fail-closed tenant scoping built into `JpaAuditLogQueryService` itself (not a caller-suppliable
  `AuditQuery` field) once `audit.log.multi-tenancy.enabled=true`; `tenant_id` threaded through the
  server module's ingest wire format and `AuditIngestController`'s
  `audit.log.server.multi-tenancy.required` enforcement. Off by default - zero behavior change on
  upgrade. See "Decisions" #14 and `MIGRATION.md`'s "Multi-tenancy" row for the full shape.

`mvn clean install` must stay green across all 7 modules - see "How to verify" below for the exact
commands; module/test counts aren't repeated here to avoid drifting stale as WPs are added.

## How to verify you're in a good state

```bash
mvn clean install                                             # all modules, must be green
mvn -pl audit_log/audit-log-spring-boot-autoconfigure test     # core starter's tests
mvn -pl audit_log/audit-log-spring-boot-server test             # REST server module's tests
mvn -pl audit_log/audit-log-java-client test                    # client module's tests (spins up
                                                                  # the real server at a random port)
cd audit_log_usage_example && mvn spring-boot:run                # then curl localhost:8080/test
```

Requires **JDK 21** (not 25 - see "Known constraints"). The `audit-log-server-proto` module
downloads a `protoc` binary via `os-maven-plugin`/`protobuf-maven-plugin` on first build - this
needs outbound access to Maven Central (or a mirror); verified working in this session's sandboxed
environment via its pre-configured proxy.

## Repository layout

```
pom.xml                                        aggregator (version 2.0.0-SNAPSHOT)
MIGRATION.md                                   1.x -> 2.x API + schema mapping
docs/SCALING.md                                large-data operation: pagination, retention, partitioning
docs/CLIENT_CODEGEN.md                         generating a client for the REST server, any language
db/migration/V2__audit_log_v2.sql              1.x -> 2.x schema migration (PostgreSQL dialect)
audit_log/
  pom.xml                                      parent for all 5 starter/server modules
  audit-log-spring-boot-autoconfigure/         core implementation code + tests + README
  audit-log-spring-boot-starter/               pom-only aggregator; what consumers depend on
  audit-log-server-proto/                      .proto IDL + generated Java stubs, no Spring dep
  audit-log-spring-boot-server/                optional REST ingestion/query server (off by default)
  audit-log-java-client/                       typed Java HTTP client for the server module
audit_log_usage_example/                       runnable demo app + integration test
```

Package root: `io.github.bitaron.auditlog` (all-lowercase - it was `auditLog` in 1.x). The server/
client modules use `io.github.bitaron.auditlog.server` / `.client` / `.server.proto.v1`.

## What each class is for

### Core (`audit-log-spring-boot-autoconfigure`)

| Package | Class | Role |
|---|---|---|
| `annotation` | `Audit` | The user-facing annotation. `templates()`, `actorSource()`, `actorExpression()`, `auditType()`, `actionName()`, `actionType()`, `groupName()`, **`mode()`** (v3: per-call delivery override) |
| | `ActorSource` | `CONTEXT` / `SYSTEM` / `EXPRESSION` |
| | `AuditDeliveryMode` | **(v3)** `INHERIT` / `ASYNC` / `SYNC` - `Audit#mode()`'s type, distinct from `AuditLogProperties.DeliveryMode` on purpose (see "Decisions") |
| | `Audits` | `@Repeatable` container for `Audit` - **now fully processed**, see "Decisions" #7 below (this was a known limitation in the v2 handoff; fixed in v3/WP8) |
| | `AuditIgnore` | Marks a parameter to be replaced with a placeholder before serialization |
| `autoconfigure` | `AuditLogAutoConfiguration` | Everything is wired here; every bean is `@ConditionalOnMissingBean` |
| | `AuditLogEntityScanRegistrar` | Adds the starter's entities to the host's scan *additively* - do not regress this |
| | `AuditLogSecurityContextConfiguration` | `SecurityContextHolder`-backed actor getter when Spring Security is present |
| | `AuditLogMicrometerConfiguration` | Real metrics recorder when Micrometer is present |
| `contract` | `AuditTemplateSource` | SPI: where template text comes from |
| | `AuditLogTemplateResolver` | SPI: how template text is rendered |
| | `AuditLogArgumentSerializer` | SPI: how args/result become the `data` JSON |
| | `AuditLogGenericDataGetter` | SPI: actor/client resolution for `ActorSource.CONTEXT` |
| | `AuditLogLocationResolver` | SPI: IP -> geographic location (none bundled) |
| | `AuditMetricsRecorder` | SPI: delivery-outcome counters |
| | **`AuditLogRecorder`** | **(v3/WP12)** Programmatic write facade - record an event with no `@Audit` join point |
| | **`AuditTenantResolver`** | **(WP15)** SPI: resolves the current tenant, for both write-time tagging and read-time scoping |
| `core` | `AuditLogAspect` | Single `@Around` advice, `@Order(LOWEST_PRECEDENCE - 1)`. **(v3)** pointcut now matches both `@Audit` and the synthetic `@Audits` container; resolves every declared instance via `AnnotatedElementUtils.findMergedRepeatableAnnotations` instead of binding one |
| | `AuditContextResolver` / `DefaultAuditContextResolver` | The **only** place that reads ambient request state |
| | `AuditLogger` | Delivery-mode dispatch: SYNC direct, ASYNC deferred to `afterCommit` when a tx is active. **(v3)** `effectiveMode()` resolves `Audit#mode()` against the global default first |
| | `AuditLogWriter` | `@Transactional` persistence. Two entry points (`persistRequiresNew` / `persistShared`) - separate bean from `AuditLogger` so the proxy is actually invoked |
| | `AuditLogTaskExecutor` | Dedicated pool; graceful shutdown accounting + MDC propagation |
| | `AuditTemplateValidator` | Opt-in startup validation of `@Audit(templates=...)` |
| | **`AuditSchemaValidator`** | **(v3/WP10)** Opt-in-by-default startup check that the 4 required tables exist; raw JDBC, one connection per table |
| | **`AuditLogRetentionService`** | **(v3/WP11)** Opt-in scheduled, batched deletion of old rows; owns its own `ThreadPoolTaskScheduler`, not `@EnableScheduling` |
| | **`DefaultAuditLogRecorder`** | **(v3/WP12)** Builds `AuditContext` directly + synthesizes an `Audit` annotation via `AnnotationUtils.synthesizeAnnotation` to reuse `AuditLogWriter`/`AuditLogger` unchanged |
| | `FreemarkerTemplateResolver` | Default renderer; LRU-bounded compiled-template cache, `?api` disabled, `SAFER_RESOLVER` |
| | `JacksonAuditLogArgumentSerializer` | Default serializer; placeholders, masking, valid-JSON truncation |
| | **`DefaultAuditTenantResolver`** | **(WP15)** Header-based default (`audit.log.headers.tenant-id`), only registered when multi-tenancy is enabled |
| `model` | `AuditContext` | Immutable record passed through the whole pipeline. **(WP15)** trailing `tenantId` field |
| | **`AuditEventRequest`** | **(v3/WP12)** Immutable record for `AuditLogRecorder#record` - the non-AOP write path's input. **(WP15)** trailing `tenantId` field |
| `entity` | `AuditLog` | One row per invocation. `@Immutable`, id-based equals/hashCode. **(WP15)** nullable `tenantId`/`tenant_id` |
| | `AuditLogMessage` | Child rows: one per rendered template, keyed by `templateName` |
| | `AuditOutcome` | `SUCCESS` / `FAILURE` |
| `query` | `AuditLogQueryService` / `JpaAuditLogQueryService` | The supported read API. **(v3)** page-size cap, sort whitelist, `findAfter` keyset pagination. **(WP15)** mandatory, fail-closed tenant predicate prepended internally when multi-tenancy is enabled - not an `AuditQuery` field |
| | `AuditQuery` / `AuditRecord` | Filter + immutable projection. **(WP15)** `AuditRecord` gains a trailing `tenantId`; `AuditQuery` deliberately unchanged (see above) |
| | **`AuditCursor`** | **(v3/WP11)** `(createdAt, id)` position for `findAfter` |
| `properties` | `AuditLogProperties` | `@Validated @ConfigurationProperties("audit.log")`. Nested: `Headers` (**WP15**: `tenantId`), `Executor`, `SchemaValidation`, `Query`, `Retention` (v3), **`MultiTenancy`** (WP15) |

### Server/client modules (v3/WP13-14)

| Module | Package | Class | Role |
|---|---|---|---|
| `audit-log-server-proto` | `server.proto.v1` | `AuditEventRequest`/`AuditEventResponse`/`AuditRecordProto`/`AuditQueryRequest`/`AuditQueryResponse`/`AuditOutcomeProto` | Generated from `audit_event.proto`; no Spring dependency |
| `audit-log-spring-boot-server` | `server` | `AuditLogServerAutoConfiguration` | Gated by `audit.log.server.enabled` (default `false`, no `matchIfMissing`); registers `ProtobufHttpMessageConverter` |
| | | `AuditIngestController` | `POST /audit-log/events` -> `AuditLogRecorder` |
| | | `AuditQueryController` | `GET /audit-log/records` -> `AuditLogQueryService` |
| | | `ApiKeyAuthFilter` | Requires `X-API-Key` matching `audit.log.server.api-key` on every `/audit-log/*` request |
| | | `AuditServerExceptionHandler` | Maps `IllegalArgumentException`/`IllegalStateException` -> `400` |
| | | `ProtoMapper` | Wire<->domain type mapping, kept in one place. **(WP15)** maps `tenant_id` both directions |
| | | `AuditLogServerProperties` | **(WP15)** nested `MultiTenancy.required` - reject ingest with a blank `tenant_id` |
| `audit-log-java-client` | `client` | `AuditLogHttpClient` | Thin `RestClient` wrapper; the module a Java consumer of server mode depends on |

## Decisions that are load-bearing - do not "simplify" these

Each of these was chosen against an obvious-looking alternative that is actually wrong. Changing
one back will reintroduce a real bug. **1-6 are from the v2 pass; 7-13 are new in v3.**

1. **`AuditLogAutoConfiguration` never registers an `EntityManager`-typed bean.** Internal
   consumers each build their own shared-EntityManager proxy from `EntityManagerFactory` via the
   private `sharedEntityManager(...)` helper. Both `@Bean(defaultCandidate = false)` and
   `@Bean(autowireCandidate = false)` were tried and **empirically fail**. Guarded by
   `starterAddsNoEntityManagerTypedBeanToTheHostContext`.

2. **`jakarta.validation-api` is `provided` scope, not compile.** Spring Boot's
   `ConfigurationPropertiesBinder` tries to build a JSR-303 validator whenever
   `jakarta.validation.Validator` is *visible on the classpath* and throws
   `NoProviderFoundException` if no implementation is present.

3. **`AuditLogAspect` is `@Order(Ordered.LOWEST_PRECEDENCE - 1)`.** One step ahead of the default
   `@Transactional` advisor order, so it deterministically wraps *outside* transactional advice.

4. **`AuditLogWriter` is a separate bean from `AuditLogger`.** Self-invocation would bypass the
   `@Transactional` proxy entirely. The same reasoning is why `AuditLogRetentionService` (v3) uses
   `TransactionTemplate` instead of a `@Transactional` method on itself - it only has one method
   that needs a transaction, and self-invocation from `runOnce()` would bypass any proxy anyway.

5. **The executor is not `@Async`/`@EnableAsync`.** Turning on async proxying is a context-wide,
   consumer-visible change a library shouldn't impose. **(v3)** `AuditLogRetentionService` applies
   the identical reasoning to scheduling: it owns its own `ThreadPoolTaskScheduler` rather than
   using `@Scheduled`/`@EnableScheduling`.

6. **`AuditLogEntityScanRegistrar` adds to - never replaces - the host's entity scan.** Guarded by
   `hostApplicationOwnEntityAndRepositoryStillDiscovered`.

7. **`Audits`/repeated `@Audit` is now fully processed (v3/WP8) - the pointcut no longer binds a
   single annotation instance.** `AuditLogAspect`'s `@Around` matches either representation the
   compiler can produce (`@annotation(Audit) || @annotation(Audits)`) without binding either as a
   parameter; the advice resolves every instance via
   `AnnotatedElementUtils.findMergedRepeatableAnnotations(method, Audit.class)` and dispatches one
   independently-isolated record per instance. If you see code trying to bind `Audit actLog`
   directly on the pointcut again, that's the v2-era bug reintroduced - it only fires the first of
   several stacked annotations. Guarded by `AuditLogAspectTest`.

8. **`AuditDeliveryMode` (the `Audit#mode()` type) is a distinct enum from
   `AuditLogProperties.DeliveryMode`, not a reuse.** The annotation attribute's type becomes part
   of every consumer's compiled bytecode; coupling it to the properties package for two overlapping
   enum constants isn't worth it, and `INHERIT` is meaningless for the global property itself.

9. **`AuditSchemaValidator` probes via raw JDBC (a fresh `Connection` per table), never JPA.** A
   `PersistenceException` from one missing-table probe would poison that `EntityManager`'s
   transaction for every table checked afterward - raw JDBC sidesteps entity-manager lifecycle
   handling entirely for what's a four-query check.

10. **`JpaAuditLogQueryService.find`'s sort is whitelisted to `id`/`createdAt`/`actorId`/
    `auditType`.** These are the only indexed/filterable `AuditLog` columns; accepting arbitrary
    sort properties would both build unvalidated JPQL from caller input and force full sorts on
    unindexed columns.

11. **`AuditLogRecorder`'s implementation synthesizes a real `Audit` annotation instance via
    Spring's `AnnotationUtils.synthesizeAnnotation` (a dynamic proxy) rather than duplicating
    `AuditLogWriter`/`AuditLogger`'s dispatch logic for a caller with no join point.** This was
    verified empirically (`AuditLogRecorderTest`) to produce byte-for-byte the same
    `AuditLog`/`AuditLogMessage` shape as the real `@Audit` + AOP path.

12. **`audit-log-spring-boot-server` depends on `audit-log-spring-boot-starter`, not
    `audit-log-spring-boot-autoconfigure` directly.** `AuditLogAutoConfiguration` always attempts
    to register an `AuditLogAspect` bean (only conditioned on `audit.log.enabled`, which this
    module needs `=true`), and that bean's class references AspectJ types at the bytecode level -
    loading it without `spring-boot-starter-aop` actually on the runtime classpath throws
    `NoClassDefFoundError`, not a graceful skip. **Verified empirically** - this exact mistake was
    made and caught during this session; see the git history on this branch.

13. **`ProtobufHttpMessageConverter`'s JSON support needs `protobuf-java-util` (for
    `com.google.protobuf.util.JsonFormat`) on the classpath - a runtime classpath probe, not a
    declared dependency of the converter class itself.** Without it, JSON requests to the server
    module silently get `415`, not an error naming what's missing. **Also verified empirically** -
    caught the same way as #12, via a failing `AuditLogServerIntegrationTest` run before the fix.

14. **`AuditTenantResolver` is its own SPI (WP15), not a method on `AuditLogGenericDataGetter`,
    and `JpaAuditLogQueryService` enforces tenant scoping internally rather than accepting a
    caller-suppliable `tenantId` on `AuditQuery`.** Tenant identity is orthogonal to actor
    identity - a `SYSTEM`-actor scheduled job still runs on behalf of one tenant - so it's resolved
    unconditionally in `DefaultAuditContextResolver`, not nested in the `actorSource` switch.
    Putting the mandatory predicate inside the query service itself (resolved fresh per call,
    failing closed when unresolvable) rather than on `AuditQuery` is what makes cross-tenant
    leakage structurally hard: there is no per-call filter for a future caller to forget to pass.
    The REST server's `AuditQueryRequest` proto deliberately has no caller-suppliable `tenant_id`
    for the same reason, one level up - see `audit_event.proto`'s comment on that message.

## Test inventory (what's actually guarded)

### v2 (unchanged)

| Test | Guards |
|---|---|
| `AuditLogAutoConfigurationTest` | Bean registration, `audit.log.enabled=false`, user overrides, host entity/repository discovery, no `EntityManager` bean added, `AutoConfiguration.imports` names a loadable class, properties-only templates, `fail-on-missing-template` both ways, executor-size validation, query service |
| `AuditLogWriterTest` | Rendering, missing/broken templates, one-row-per-event with N messages, `duration_ms`, `data` excludes actor fields, masking, servlet-arg safety, rollback leaves zero rows (ASYNC + SYNC), commit persists |
| `AuditLoggerTest` | Dispatch mode matrix, commit deferral, rollback never dispatches, failure/rejection counted not propagated, **(v3) per-call mode override wins over the global default, both directions** |
| `AuditLogAspectTransactionOrderingTest` | Real `@Transactional @Audit` proxy: business write rolls back, audit still records `FAILURE`; commit path records `SUCCESS` |
| `AuditLogTaskExecutorTest` | Shutdown accounting, MDC propagation |
| `FreemarkerTemplateResolverTest` | Rendering, cache reuse, bounded cache, `?api` blocked |
| `JacksonAuditLogArgumentSerializerTest` | Placeholders, `Throwable` compaction, deep masking, valid-JSON truncation |
| `AuditLogTestControllerIntegrationTest` (demo) | Full stack via MockMvc: outcome, duration, child messages, exception propagation |

### v3 (new this pass)

| Test | Guards |
|---|---|
| `AuditLogAspectTest` | **WP8**: two stacked `@Audit` on one method each dispatch independently, through real AOP; **WP9**: a per-call `SYNC` override is visible on the caller's thread with no async wait, through real AOP |
| `AuditSchemaValidatorTest` | **WP10**: missing tables fail startup with a message naming them + the migration file path; present tables start cleanly; `enabled=false` skips the check |
| `JpaAuditLogQueryServiceTest` | **WP11**: oversized page size and unrecognized sort property both rejected; keyset pagination returns stable, non-overlapping pages |
| `AuditLogRetentionServiceTest` | **WP11**: batched purge deletes only rows older than the cutoff, including child messages; newer rows survive |
| `AuditLogRecorderTest` | **WP12**: `record(...)` produces the same `AuditLog`/`AuditLogMessage` shape as an equivalent `@Audit`-annotated call |
| `AuditLogServerAutoConfigurationTest` | **WP13**: disabled by default (no controllers registered); enabling without an API key fails startup; both controllers registered once configured |
| `AuditLogServerIntegrationTest` | **WP13**: real `MockMvc` round-trip through both JSON and binary-`application/x-protobuf` ingest, `401` without the API key, query returns the ingested row |
| `AuditLogHttpClientTest` | **WP14**: starts the real server module at a random port; one `ingest` + one `query` call round-trips through the generated Protobuf types with zero manual (de)serialization in the test |

### WP15 (this pass)

| Test | Guards |
|---|---|
| `AuditLogAutoConfigurationTest` | No `AuditTenantResolver` bean by default; `DefaultAuditTenantResolver` registered when `audit.log.multi-tenancy.enabled=true`; a user-supplied bean overrides it |
| `JpaAuditLogQueryServiceTest` | Disabled-by-default: rows across all tenants still returned unfiltered; enabled: reads are scoped to only the resolved tenant, via both `find` and `findAfter`; an unresolvable tenant fails closed (`IllegalStateException`), never falling back to an unscoped query |
| `AuditLogServerIntegrationTest` | `tenant_id` round-trips through ingest with the server-local `multi-tenancy.required` flag left off |
| `AuditLogServerMultiTenancyIntegrationTest` | `multi-tenancy.required=true` rejects a blank `tenant_id` on ingest (`400`); a real HTTP round trip proves two tenants' ingested rows stay isolated under `GET /audit-log/records`, scoped by the `X-TENANT-ID` header; a missing header is rejected the same way as the unit-level fail-closed case |

## Not done (deliberately)

### From the v2 pass's "out of scope"

- **Transactional outbox / guaranteed delivery.** `audit.log.mode=SYNC` covers the compliance case.
- **Replacing FreeMarker with SpEL for message bodies.** SpEL is only used for `actorExpression`.
- **Publishing 2.0.0 to Maven Central.** The `release` profile exists but needs GPG + Sonatype
  credentials. Version is still `2.0.0-SNAPSHOT`.
- **JDK 25.** Blocked on Lombok (see below).

### From the v3 pass's "out of scope" (see the plan file's full rationale for each)

- **Export & right-to-erasure (CSV export, actor-scoped purge).** Explicitly excluded per user
  instruction for this round - not designed at all, not even stubbed. If it comes back,
  `AuditLogRetentionService`'s batched-delete mechanics are the natural thing to extend.
- **Multi-tenancy.** Implemented in WP15 (see below) - no longer deferred.
- **Alerting/webhooks, pluggable non-JPA storage.** Each is a separate, larger design decision than
  any pass's scope so far.
- **A runtime `audit.log.id-generation=IDENTITY|SEQUENCE` property**, despite being mentioned in
  the original plan text for WP11. Deliberately **not implemented** after closer analysis: JPA's
  `@GeneratedValue` strategy is effectively fixed at entity-mapping time, and a property that
  silently "just switches" the ID strategy at runtime either lies about that or races the real
  schema migration switching strategies actually requires. `docs/SCALING.md` documents the
  IDENTITY-vs-batching limitation and the exact migration SQL a consumer would apply to fork the
  entities themselves - judged more honest than shipping a fragile/misleading property. **Flag this
  to the user if they specifically wanted the property, not just the documentation.**
- **Message-queue-based server ingestion (Kafka/RabbitMQ consumer).** REST only for this round; a
  future MQ consumer would reuse `AuditLogRecorder` (WP12) and the same `.proto` schema.
- **A gRPC service on the same `.proto` schema.** REST was the explicit ask; the schema is written
  so gRPC could reuse it later without a rewrite, but none exists today.
- **Pre-built/published codegen packages for non-Java languages.** `docs/CLIENT_CODEGEN.md` makes
  self-service `protoc` codegen work (verified end-to-end for Python); publishing per-language
  packages to PyPI/npm/etc. is a packaging decision, not attempted.
- **Build-time (Maven-plugin) DB schema check.** Only the startup check (`AuditSchemaValidator`,
  WP10) exists; a build-time variant needs its own design (CI credentials, which phase to bind to).

### From WP15's out of scope (multi-tenancy)

- **Per-tenant server authentication/authorization.** WP15 threads tenant data through and enforces
  it at the DB query level, but the server module's `ApiKeyAuthFilter` is still a single shared
  secret with no per-caller identity - proving a given API caller is actually allowed to act
  as/read a given tenant still rests entirely on whatever sets `X-TENANT-ID` (trusted only if a
  gateway sets it, same as today's actor-header trust model). Real per-tenant access control needs
  per-tenant API keys or JWT/claims-based auth - a materially bigger change, not attempted here.
- **`AuditTemplate`/`AuditGroup` staying global (not tenant-scoped).** Templates are code-like,
  versioned with the application, not tenant data; the sensitive rows under a group are already
  tenant-tagged via `audit_log.tenant_id`. Per-tenant custom templates or group namespacing would
  need composite unique constraints and a tenant parameter threaded through `AuditTemplateSource`
  implementations - not designed here; flag explicitly if it's actually needed.
- **`AuditLogMessage` gains no `tenant_id`.** It's always looked up via `audit_log_id` from an
  already tenant-scoped `AuditLog` row (`JpaAuditLogQueryService` never queries it independently),
  so a tenant column there would be pure redundant denormalization today - revisit only if that
  access pattern changes.
- **Automatic per-tenant retention/purge scoping.** `AuditLogRetentionService`'s batched delete
  still purges by age alone, across all tenants uniformly - not tenant-aware.

## Known constraints and limitations

- **JDK 21 only.** Lombok doesn't generate members correctly under JDK 25's compiler internals.
- **JSR-303 constraints are unenforced without a validator on the consumer's classpath.** By
  design (see decision #2), but it means `@Min` violations bind silently in that case.
- **`fail-on-missing-template` scans every bean's methods at startup.** Off by default for that
  reason.
- **`trace_id` is read from MDC key `traceId`.** A consumer using a different key gets nulls.
- **`V2__audit_log_v2.sql` is PostgreSQL dialect** and backfills `outcome='SUCCESS'` for
  pre-existing rows. Adjust for other databases.
- **(v3) `AuditSchemaValidator` checks table *existence* only, not column-level schema drift.** A
  table present but missing a v2-era column (e.g. `outcome`) would pass this check and fail later
  at the first write - a deliberate scope limit (see `AuditSchemaValidator`'s javadoc), not a bug.
- **(v3) `AuditEventRequest`'s `args`/`result`/`exception` fields, when populated via the REST
  server's `args_json`/`result_json`/`exception_json` proto fields, are stored as opaque,
  string-escaped JSON inside the `data` column - not structurally merged.** Documented as a v1
  limitation directly in `audit_event.proto`; a future revision could accept a
  `google.protobuf.Struct` for true structural passthrough without a breaking change.
- **(v3) `ApiKeyAuthFilter` is a single static shared secret** - no per-caller identity, rotation,
  or revocation. Explicitly documented as a first cut on `AuditLogServerProperties.apiKey`'s
  javadoc; production deployments should front the server module with real authn/authz.
- **(v3) The `audit-log-server-proto` module's build requires downloading a `protoc` binary
  on first build** (via `os-maven-plugin`'s OS/arch detection + `protobuf-maven-plugin`). Works
  from Maven Central; an air-gapped/offline build environment would need a local mirror or a
  pre-populated `~/.m2` cache.

## Git state

- Branch `claude/project-audit-planning-ztbevf` was **restarted from `main`** at the start of this
  (v3) pass, since the branch's prior (v2) content had already been merged - per the task's
  branch-reuse rule, a merged branch is never stacked on top of, it's rebuilt from the current
  default branch.
- v2 (WP0-WP7) is merged into `main` already (see the merge commit preceding this branch's own
  history). v3 (WP8-WP14, this pass) is **not yet merged** as of this commit - it's pushed to
  `claude/project-audit-planning-ztbevf`, ready for review/PR.
- The plan this pass followed is at `/root/.claude/plans/go-through-this-project-starry-bear.md`
  (agent-local, not in the repo) - WP8 through WP14, all complete, one commit per WP.
