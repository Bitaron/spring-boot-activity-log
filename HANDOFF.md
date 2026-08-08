# Handoff: audit-log 2.0 redesign + v3 (server mode, safety rails, scale)

Status as of the last commit on `claude/project-audit-planning-ztbevf`. Written so another agent
(or human) can pick this up cold. For the *user-facing* API mapping see
[`MIGRATION.md`](MIGRATION.md); for high-volume operation see
[`docs/SCALING.md`](docs/SCALING.md); for generating a client in another language see
[`docs/CLIENT_CODEGEN.md`](docs/CLIENT_CODEGEN.md). This document is about the state of the work
itself.

## TL;DR

Seven passes are **complete**:

- **v2** (WP0-WP7): the architecture/API redesign - package rename, annotation redesign, commit-
  aware dispatch, data model fix, typed config, read API. See the "v2" sections below.
- **v3** (WP8-WP14): per-call delivery override, startup schema validation, large-data
  handling (pagination/retention/partitioning docs), a programmatic write facade, an optional
  Protobuf REST server, and client codegen support.
- **WP15: opt-in multi-tenancy (writes/reads).** New `AuditTenantResolver` SPI (its own interface,
  not folded into `AuditLogGenericDataGetter` - tenant identity is orthogonal to actor identity);
  a nullable `audit_log.tenant_id` column (`V3__audit_log_multi_tenancy.sql`); mandatory,
  fail-closed tenant scoping built into `JpaAuditLogQueryService` itself (not a caller-suppliable
  `AuditQuery` field) once `audit.log.multi-tenancy.enabled=true`. Off by default - zero behavior
  change on upgrade. See "Decisions" #14 and `MIGRATION.md`'s "Multi-tenancy" row for the full shape.
- **WP16 (this pass): finishes what WP15 explicitly deferred** - per-tenant server
  authentication, tenant-scoped templates/groups, tenant-aware retention. **Not backward
  compatible** (explicitly not required for this pass, unlike every WP before it): the server
  module's single shared `audit.log.server.api-key` is gone, replaced by per-tenant
  `audit.log.server.api-keys.<tenantId>`, and `AuditTemplateSource.findTemplate` gained a
  `tenantId` parameter. See "Decisions" #15-16 and `MIGRATION.md`'s "Breaking changes in WP16"
  section for the full shape.
- **WP17: client/API ergonomics pass** (WP17a-k, 11 commits) - purely additive, no breaking
  changes. Builder/factory ergonomics (`AuditEventRequest.Builder`, `AuditQuery` static
  factories/withers, `AuditRecord.toCursor()`); `AuditLogGenericDataGetter`'s 5 methods became
  `default`; a published `testsupport.AuditLogAssertions` test-support artifact (the `tests`
  classifier); `AuditLogHttpClient` gained an injectable `RestClient.Builder` constructor, a typed
  `exception.AuditLogClientException` hierarchy, and keyset pagination (`queryAfter`, plus the
  matching `GET /audit-log/records/after` server endpoint and `AuditCursorQueryRequest`/`Response`
  proto messages); a new `audit-log-java-client-spring-boot-starter` module auto-registers
  `AuditLogHttpClient` from `audit.log.client.*` properties. Writing the client's error-handling
  test surfaced and fixed a real pre-existing server bug - see "Decisions" #17.
- **WP18: a gRPC service on the same `audit_event.proto` schema** - the thing WP14's "not done"
  list explicitly flagged as deferred. Purely additive, no breaking changes: `audit_event.proto`
  gained a `service AuditLogService` block (`Ingest`/`Query`/`QueryAfter`) reusing every existing
  message unchanged, a second `protoc` codegen pass (`grpc-java` plugin) produces
  `AuditLogServiceGrpc`, and a new `audit-log-spring-boot-grpc-server` module implements it on top
  of the same `AuditLogRecorder`/`AuditLogQueryService` beans the REST server module already uses -
  same per-tenant-API-key authentication model, ported to gRPC metadata + `io.grpc.Context`. Off by
  default (`audit.log.grpc.enabled=false`) and **cannot be enabled alongside the REST server
  module** in the same application - see "Decisions" #18.
- **WP19: documentation pass** - no source behavior changed, only documentation and build tooling.
  New [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) (every `audit.log.*` property, one canonical
  table per module, superseding scattered duplicates); a hand-authored OpenAPI spec
  (`audit-log-server-openapi.yaml`) plus a bundled static Swagger UI page for the REST server module
  (`/swagger-ui/index.html` on a running instance) - deliberately not springdoc-generated, see
  "Decisions" #19 for why; and a new GitHub Pages docs site
  ([`.github/workflows/pages.yml`](.github/workflows/pages.yml) +
  [`.github/pages-site/`](.github/pages-site)) aggregating Javadoc across every library module, the
  Swagger spec, and every Markdown doc rendered client-side, published on every push to `main`.

`mvn clean install` must stay green across all 9 modules - see "How to verify" below for the exact
commands; module/test counts aren't repeated here to avoid drifting stale as WPs are added.

## How to verify you're in a good state

```bash
mvn clean install                                             # all modules, must be green
mvn -pl audit_log/audit-log-spring-boot-autoconfigure test     # core starter's tests
mvn -pl audit_log/audit-log-spring-boot-server test             # REST server module's tests
mvn -pl audit_log/audit-log-spring-boot-grpc-server test        # gRPC server module's tests (WP18;
                                                                  # spins up a real gRPC server at an
                                                                  # ephemeral port)
mvn -pl audit_log/audit-log-java-client test                    # client module's tests (spins up
                                                                  # the real server at a random port)
mvn -pl audit_log/audit-log-java-client-spring-boot-starter test # client auto-config module's tests
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
  pom.xml                                      parent for all 7 starter/server modules
  audit-log-spring-boot-autoconfigure/         core implementation code + tests + README
  audit-log-spring-boot-starter/               pom-only aggregator; what consumers depend on
  audit-log-server-proto/                      .proto IDL + generated Java stubs (Protobuf + gRPC), no Spring dep
  audit-log-spring-boot-server/                optional REST ingestion/query server (off by default)
  audit-log-spring-boot-grpc-server/           optional gRPC ingestion/query server (WP18, off by default)
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
| `contract` | `AuditTemplateSource` | SPI: where template text comes from. **(WP16)** `findTemplate(String tenantId, String name)` - breaking signature change, see `MIGRATION.md` |
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
| | `AuditTemplateValidator` | Opt-in startup validation of `@Audit(templates=...)`. **(WP16)** only validates the tenant-agnostic/global layer - documented, deliberate false-positive risk for tenant-only templates |
| | **`AuditSchemaValidator`** | **(v3/WP10)** Opt-in-by-default startup check that the 4 required tables exist; raw JDBC, one connection per table |
| | **`AuditLogRetentionService`** | **(v3/WP11)** Opt-in scheduled, batched deletion of old rows; owns its own `ThreadPoolTaskScheduler`, not `@EnableScheduling`. **(WP16)** purges once per distinct tenant present in `audit_log`, each against its own effective cutoff (`retention.tenant-max-age.<tenantId>` or the global `retention.max-age`) |
| | **`DefaultAuditLogRecorder`** | **(v3/WP12)** Builds `AuditContext` directly + synthesizes an `Audit` annotation via `AnnotationUtils.synthesizeAnnotation` to reuse `AuditLogWriter`/`AuditLogger` unchanged |
| | `FreemarkerTemplateResolver` | Default renderer; LRU-bounded compiled-template cache, `?api` disabled, `SAFER_RESOLVER` |
| | `JacksonAuditLogArgumentSerializer` | Default serializer; placeholders, masking, valid-JSON truncation |
| | **`DefaultAuditTenantResolver`** | **(WP15)** Header-based default (`audit.log.headers.tenant-id`), only registered when multi-tenancy is enabled |
| | **`PropertiesAuditTemplateSource`** / **`DatabaseAuditTemplateSource`** | **(WP16)** Tenant-scoped: a tenant-tagged template wins over the global one for that tenant, global as fallback |
| `model` | `AuditContext` | Immutable record passed through the whole pipeline. **(WP15)** trailing `tenantId` field |
| | **`AuditEventRequest`** | **(v3/WP12)** Immutable record for `AuditLogRecorder#record` - the non-AOP write path's input. **(WP15)** trailing `tenantId` field |
| `entity` | `AuditLog` | One row per invocation. `@Immutable`, id-based equals/hashCode. **(WP15)** nullable `tenantId`/`tenant_id` |
| | `AuditLogMessage` | Child rows: one per rendered template, keyed by `templateName` |
| | `AuditOutcome` | `SUCCESS` / `FAILURE` |
| | `AuditTemplate` / `AuditGroup` | **(WP16)** Tenant-scoped: `tenantId` is `""` (`GLOBAL_TENANT_ID`), never `null` - see "Decisions" #15. Composite `(tenant_id, name)` unique constraint, replacing the old name-only one |
| `query` | `AuditLogQueryService` / `JpaAuditLogQueryService` | The supported read API. **(v3)** page-size cap, sort whitelist, `findAfter` keyset pagination. **(WP15)** mandatory, fail-closed tenant predicate prepended internally when multi-tenancy is enabled - not an `AuditQuery` field |
| | `AuditQuery` / `AuditRecord` | Filter + immutable projection. **(WP15)** `AuditRecord` gains a trailing `tenantId`; `AuditQuery` deliberately unchanged (see above) |
| | **`AuditCursor`** | **(v3/WP11)** `(createdAt, id)` position for `findAfter` |
| `properties` | `AuditLogProperties` | `@Validated @ConfigurationProperties("audit.log")`. Nested: `Headers` (**WP15**: `tenantId`), `Executor`, `SchemaValidation`, `Query`, `Retention` (v3, **WP16**: `tenantMaxAge`), **`MultiTenancy`** (WP15). **(WP16)** top-level `tenantTemplates` map |

### Server/client modules (v3/WP13-14, WP17, WP18)

| Module | Package | Class | Role |
|---|---|---|---|
| `audit-log-server-proto` | `server.proto.v1` | `AuditEventRequest`/`AuditEventResponse`/`AuditRecordProto`/`AuditQueryRequest`/`AuditQueryResponse`/`AuditOutcomeProto` | Generated from `audit_event.proto`; no Spring dependency. **(WP17)** `AuditCursorQueryRequest`/`AuditCursorQueryResponse` for keyset pagination. **(WP18)** `AuditLogServiceGrpc` (stub + `ImplBase`) generated by a second `protoc` pass now that the `.proto` file also declares `service AuditLogService` |
| `audit-log-spring-boot-server` | `server` | `AuditLogServerAutoConfiguration` | Gated by `audit.log.server.enabled` (default `false`, no `matchIfMissing`); registers `ProtobufHttpMessageConverter`. **(WP16)** `@AutoConfigureBefore(AuditLogAutoConfiguration.class)` so its `AuditTenantResolver` wins the `@ConditionalOnMissingBean` race; also fails startup unless `audit.log.multi-tenancy.enabled=true`. **(WP17)** now also explicitly registers `AuditServerExceptionHandler` as a `@Bean` - see "Decisions" #17. **(WP18)** also fails startup if `audit.log.grpc.enabled=true` - see "Decisions" #18 |
| | | `AuditIngestController` | `POST /audit-log/events` -> `AuditLogRecorder`. **(WP16)** tenant comes from `AuditTenantResolver` (the authenticated one), not the wire `tenant_id` - a mismatched body value is rejected (`400`), never silently overridden |
| | | `AuditQueryController` | `GET /audit-log/records` -> `AuditLogQueryService`. **(WP17)** also `GET /audit-log/records/after` -> `AuditLogQueryService#findAfter`; `cursorCreatedAt`/`cursorId` must both be supplied together or neither (`400` if only one) |
| | | `ApiKeyAuthFilter` | **(WP16)** Per-tenant: `X-API-Key` must match one of `audit.log.server.api-keys.<tenantId>`; stashes which tenant as a request attribute for `ApiKeyAuditTenantResolver` to read |
| | | **`ApiKeyAuditTenantResolver`** | **(WP16)** The `AuditTenantResolver` this module registers - reads the tenant `ApiKeyAuthFilter` authenticated, never a client-suppliable value |
| | | `AuditServerExceptionHandler` | Maps `IllegalArgumentException`/`IllegalStateException` -> `400`. **(WP17)** writes directly to `HttpServletResponse` instead of returning a `String` for content-negotiated rendering - the latter silently `500`s for a Protobuf-only `Accept` header, since no converter can render plain text as `application/x-protobuf` |
| | | `ProtoMapper` | Wire<->domain type mapping, kept in one place. **(WP15)** maps `tenant_id` both directions. **(WP16)** `toEventRequest` takes the authenticated tenant as an explicit parameter instead of trusting `proto.getTenantId()`. **(WP17)** `toCursorQueryResponse` for the new keyset endpoint |
| | | `AuditLogServerProperties` | **(WP16, breaking)** `apiKey` (single shared secret) replaced entirely by `apiKeys` (`Map<tenantId, secret>`); the old `MultiTenancy.required` nested class is gone - see `MIGRATION.md` |
| `audit-log-java-client` | `client` | `AuditLogHttpClient` | Thin `RestClient` wrapper; the module a Java consumer of server mode depends on. Unchanged by WP16 - a tenant's key is just whatever secret string it's constructed with. **(WP17)** gained a `RestClient.Builder`-accepting constructor (the 2-arg one now delegates to it), `query(AuditQueryRequest)` (the existing 6-arg overload now delegates to it too), and `queryAfter` |
| | `client.exception` | `AuditLogClientException` + `Authentication`/`BadRequest`/`Server`/`Connection` subtypes | **(WP17)** Translates `RestClient`'s generic `RestClientResponseException`/`ResourceAccessException` hierarchy into typed exceptions a caller can branch on, via a private `execute()` wrapper |
| | `client` | `AuditRecordProtos` | **(WP17)** `createdAt(AuditRecordProto)` - parses the raw ISO-8601 wire string back to a `LocalDateTime`; can't be a method on the generated class itself |
| `audit-log-java-client-spring-boot-starter` | `client.autoconfigure` | `AuditLogClientAutoConfiguration` / `AuditLogClientProperties` | **(WP17, new module)** Gated by `audit.log.client.enabled` (default `false`); registers an `AuditLogHttpClient` bean via its `RestClient.Builder` constructor so `audit.log.client.http.*` timeouts actually take effect. A separate artifact from `audit-log-java-client` so a non-Spring-Boot consumer of the plain client never gets `spring-boot-autoconfigure` forced onto its classpath - the client module itself depends on nothing beyond `spring-web` |
| `audit-log-spring-boot-grpc-server` | `grpc` | `AuditLogGrpcServerAutoConfiguration` | **(WP18, new module)** Gated by `audit.log.grpc.enabled` (default `false`, no `matchIfMissing`). `@AutoConfigureBefore(AuditLogAutoConfiguration.class)`, identical mechanism to the REST server module, so its `AuditTenantResolver` wins the `@ConditionalOnMissingBean` race. Fails startup unless `audit.log.multi-tenancy.enabled=true`, and also if `audit.log.server.enabled=true` - see "Decisions" #18 |
| | | `AuditLogGrpcService` | `extends AuditLogServiceGrpc.AuditLogServiceImplBase` - implements `Ingest`/`Query`/`QueryAfter` on the same `AuditLogRecorder`/`AuditLogQueryService` beans the REST controllers use. Tenant mismatch on `Ingest` -> `INVALID_ARGUMENT`, unexpected exceptions -> `INTERNAL` with no detail leaked, mirroring `AuditServerExceptionHandler`'s REST-side mapping |
| | | `ApiKeyGrpcServerInterceptor` | `ServerInterceptor` reading the `x-api-key` gRPC metadata entry, resolving it against `audit.log.grpc.api-keys.<tenantId>`, rejecting with `UNAUTHENTICATED` on no match, else setting the tenant into an `io.grpc.Context` value - the gRPC-transport equivalent of `ApiKeyAuthFilter` |
| | | `GrpcAuditTenantResolver` | The `AuditTenantResolver` this module registers - reads `ApiKeyGrpcServerInterceptor`'s `Context` value, never a client-suppliable field |
| | | `GrpcProtoMapper` | Wire<->domain mapping - a **deliberate duplicate** of `audit-log-spring-boot-server`'s `ProtoMapper`, not a shared dependency; documented in its own class javadoc as a considered tradeoff (avoids forcing Servlet/MVC types onto grpc-only deployments, or gRPC types onto REST-only ones) |
| | | `AuditLogGrpcServer` | Owns the `io.grpc.Server` lifecycle via `InitializingBean`/`DisposableBean` - the same hand-rolled pattern `AuditLogRetentionService`/`AuditLogTaskExecutor` already use, not `@Scheduled` or a `grpc-spring-boot-starter` dependency. `getPort()` returns the real bound port, needed for tests that configure `audit.log.grpc.port=0` (ephemeral) |

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

15. **`AuditTemplate`/`AuditGroup.tenantId` uses `""` (`GLOBAL_TENANT_ID`), never `null`, as its
    "not tenant-specific" sentinel (WP16) - deliberately different from `AuditLog.tenantId`'s
    "`null` = default tenant" convention.** Both tables have a real composite `(tenant_id, name)`
    unique constraint, and standard SQL treats every `NULL` as distinct for uniqueness purposes -
    a `NULL`-based convention here would silently allow duplicate global template/group names.
    Also carries a `columnDefinition` (not just `nullable = false`) so a `ddl-auto`-generated
    schema gets a real SQL-level `DEFAULT ''` too - **caught empirically**: without it,
    `audit_log_usage_example`'s `data.sql` (a raw `INSERT` omitting the column) failed with a
    `NOT NULL` violation under H2's `create-drop`, since the Java field default alone only applies
    to JPA-persisted rows, not rows a hand-written SQL statement creates.
16. **The server module's per-tenant API keys authenticate a tenant, not just tag one (WP16).**
    `AuditIngestController` derives the persisted event's tenant from `AuditTenantResolver` (i.e.
    from the key), never trusting the wire `tenant_id` on its own - a body naming a *different*
    tenant than the one authenticated is rejected (`400`), not silently overridden.
    `AuditLogServerAutoConfiguration` fails startup if `audit.log.multi-tenancy.enabled` isn't also
    `true`: per-tenant keys without the core starter's tenant-scoped read enforcement would
    authenticate a tenant identity that nothing then confines reads by - misleading, not just
    incomplete. Getting `ApiKeyAuditTenantResolver` to actually be the resolver every
    `DefaultAuditContextResolver`/`JpaAuditLogQueryService` uses (not the core starter's
    header-based default) required `@AutoConfigureBefore(AuditLogAutoConfiguration.class)` on
    `AuditLogServerAutoConfiguration`, so its `@ConditionalOnMissingBean` `AuditTenantResolver`
    bean registers first.
17. **`AuditServerExceptionHandler` is registered explicitly as a `@Bean` (WP17), not left to
    component scanning.** `@RestControllerAdvice` is a scanning stereotype - whether it was ever
    picked up depended entirely on whether the host application's `@SpringBootApplication` base
    package happened to cover `io.github.bitaron.auditlog.server`. Every one of this module's own
    tests before WP17 had a test application that either did (passing by coincidence) or never
    exercised a caller-error path at all - a real host application's base package essentially never
    overlaps with this library's, so every `IllegalArgumentException`/`IllegalStateException` the
    controllers throw silently surfaced as an unhelpful `500` instead of the documented `400`.
    **Caught empirically** writing `AuditLogHttpClientErrorHandlingTest` (the client exception
    hierarchy work), whose `ClientTestServerApplication` lives in `io.github.bitaron.auditlog.client`
    - the first test application in this project whose base package *didn't* happen to overlap.
    Same fix also had to make the handler write directly to `HttpServletResponse` instead of
    returning a `String` for Spring MVC's default content-negotiated rendering, which itself fails
    (uncaught, another silent `500`) for a client sending only `Accept: application/x-protobuf`.
18. **`audit-log-spring-boot-server` (REST) and `audit-log-spring-boot-grpc-server` (gRPC, WP18)
    fail startup loudly if both are enabled in the same application, rather than silently letting
    one win.** Each authenticates its per-tenant API keys into a different, non-interoperable
    request-scoped context - an `HttpServletRequest` attribute for REST, a gRPC `Context` value for
    gRPC - and only one `AuditTenantResolver` bean can be active application-wide. Both modules use
    `@AutoConfigureBefore(AuditLogAutoConfiguration.class)` to win the `@ConditionalOnMissingBean`
    race for that bean; whichever one's autoconfiguration happens to process first would silently
    win it if both were enabled, leaving the other protocol's calls resolving no tenant at all - an
    order-dependent latent bug, not an obvious failure. Each module's own
    `Environment.getProperty("audit.log.<other>.enabled", Boolean.class, false)` check (no compile
    dependency between the two sibling optional modules) catches this at startup instead. Also
    caught while building this pass: `AuditLogGrpcService`'s use of `Page`/`Pageable`/`PageRequest`
    needs `spring-data-commons` added directly (it's `provided` scope in the autoconfigure module,
    same as the REST server module already needed), and that same "provided in autoconfigure"
    pattern applies to `jakarta.servlet-api` too - but for a less obvious reason: the core
    starter's `DefaultAuditContextResolver` bean (unconditionally registered, unrelated to gRPC's
    own tenant resolution) has a hard compile-time reference to `HttpServletRequest`, so Spring
    fails to even introspect that class without the Servlet API on the classpath - a pure-gRPC
    deployment with no MVC/Tomcat still needs that jar for that reason alone. Unlike
    `spring-data-commons`, this module supplies `jakarta.servlet-api` at real (not `provided`)
    scope so it flows to host applications automatically, since a gRPC-only deployment has no other
    reason to add it itself and shouldn't have to know this gotcha exists.
19. **The REST server's OpenAPI spec (WP19) is hand-authored, not generated by springdoc or any
    runtime-introspection tool.** The module speaks Protobuf on the wire
    (`ProtobufHttpMessageConverter`, JSON via `protobuf-java-util`'s `JsonFormat`), not plain
    Jackson JSON - a Jackson-based introspection tool would produce an inaccurate schema for the
    generated protobuf message classes, which expose internal getters
    (`getDescriptorForType`/`getSerializedSize`/`getAllFields`/...) that Jackson would treat as real
    JSON properties but that never actually appear on the wire. Considered and rejected:
    `grpc-gateway`'s `protoc-gen-openapiv2`, which derives an OpenAPI spec directly from a `.proto`
    file and would have kept the spec mechanically in sync with schema changes - but it's
    distributed as a Go binary with no Maven Central coordinate, breaking this project's existing
    convention that every codegen tool (`protoc`, `protoc-gen-grpc-java`) is a Maven-resolvable
    artifact via `os-maven-plugin` classifiers; adding a Go toolchain dependency for a 3-endpoint
    REST surface wasn't judged worth that inconsistency. Tradeoff accepted instead: the spec
    (`audit-log-server-openapi.yaml`) can drift from `AuditIngestController`/`AuditQueryController`
    if a change to either isn't also reflected in the spec by hand - there is no build-time check
    enforcing they match, unlike the actual wire format itself, which server/client both compile
    against `audit_event.proto` for.

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

### WP15

| Test | Guards |
|---|---|
| `AuditLogAutoConfigurationTest` | No `AuditTenantResolver` bean by default; `DefaultAuditTenantResolver` registered when `audit.log.multi-tenancy.enabled=true`; a user-supplied bean overrides it |
| `JpaAuditLogQueryServiceTest` | Disabled-by-default: rows across all tenants still returned unfiltered; enabled: reads are scoped to only the resolved tenant, via both `find` and `findAfter`; an unresolvable tenant fails closed (`IllegalStateException`), never falling back to an unscoped query |

### WP16 (this pass)

| Test | Guards |
|---|---|
| `AuditLogServerAutoConfigurationTest` | Startup fails with no `api-keys` configured; startup fails with keys configured but `multi-tenancy.enabled=false`; succeeds and registers `ApiKeyAuditTenantResolver` once both are satisfied |
| `AuditLogServerIntegrationTest` | Single-tenant config: unknown/missing API key rejected (`401`); the persisted tenant is the one authenticated by the key, regardless of what (if anything) the wire `tenant_id` says; a mismatched wire `tenant_id` is rejected (`400`) |
| `AuditLogServerMultiTenancyIntegrationTest` | Two tenants, two keys: each key's `GET /audit-log/records` only ever returns its own tenant's rows (the real cross-tenant-isolation guarantee, over HTTP, driven by which key authenticates - not a header); tenant-a's key cannot ingest data claiming to be tenant-b's |
| `AuditLogWriterTest` | A tenant-specific `audit_template` row overrides a same-named global one; a tenant with no override still falls back to the global row; the same `groupName` used by two different tenants creates two distinct `AuditGroup` rows, but is reused within one tenant |
| `AuditLogAutoConfigurationTest` | `audit.log.tenant-templates.<tenantId>.<name>` overrides the tenant-agnostic `audit.log.templates.<name>` property for that tenant only |
| `AuditLogRetentionServiceTest` | A tenant with a `retention.tenant-max-age` override is purged to its own cutoff while another tenant (and the no-tenant case) keeps following the global `retention.max-age` |

### WP17

| Test | Guards |
|---|---|
| `AuditQueryServiceTest`/query tests | `AuditRecord.toCursor()` produces pages identical to manually-constructed `AuditCursor`s |
| `AuditLogAssertionsTest` | The published `testsupport.AuditLogAssertions` helper itself - `awaitRecord`/`awaitRecords` correctly poll for `ASYNC`-delivered writes |
| `AuditLogTestControllerIntegrationTest` (demo app) | Rewritten to use `AuditLogAssertions` instead of raw `EntityManager`/JPQL, dogfooding the published helper |
| `AuditLogHttpClientTest` | `query(AuditQueryRequest)` (multi-filter: actor + type + created-at range combined); `queryAfter` keyset pagination through the real client |
| `AuditLogHttpClientErrorHandlingTest` | Each typed `AuditLogClientException` subtype is thrown for the right HTTP status; surfaced and drove the fix for "Decisions" #17 |
| `AuditLogHttpClientMultiTenancyTest` | A client holding one tenant's key never sees another tenant's records via `query()` or `queryAfter()` - the client-side half of `AuditLogServerMultiTenancyIntegrationTest`'s guarantee |
| `AuditLogClientAutoConfigurationTest` | No `AuditLogHttpClient` bean by default; startup fails with `enabled=true` and no `base-url`; registers the bean (with configured timeouts applied) once both are set |
| `AuditLogServerIntegrationTest`/`AuditQueryController` tests | `GET /audit-log/records/after` keyset-paginates the same way `AuditLogQueryService#findAfter` does in-process; a half-supplied cursor (`cursorCreatedAt` XOR `cursorId`) is rejected as `400` |

### WP18

| Test | Guards |
|---|---|
| `AuditLogGrpcServerAutoConfigurationTest` | Disabled by default (no `AuditLogGrpcServer` bean); startup fails with no `api-keys` configured; startup fails with keys configured but `multi-tenancy.enabled=false`; startup fails when `audit.log.server.enabled=true` too (the REST/gRPC mutual-exclusion guard); succeeds and registers `GrpcAuditTenantResolver` + a running `AuditLogGrpcServer` (real bound port `> 0`) once satisfied |
| `AuditLogGrpcServerIntegrationTest` | Real `ManagedChannel`/blocking-stub round trip: `UNAUTHENTICATED` with no/unknown `x-api-key`; `Ingest` then `Query` round-trips and the persisted tenant is the one authenticated by the key regardless of the wire `tenant_id`; a mismatched wire `tenant_id` is rejected (`INVALID_ARGUMENT`); `QueryAfter` keyset-paginates the same way the REST endpoint does; a half-supplied cursor is rejected |
| `AuditLogGrpcServerMultiTenancyIntegrationTest` | Two tenants, two keys: each key's `Query` RPC only ever returns its own tenant's rows; an unrecognized key is rejected (`UNAUTHENTICATED`); tenant-a's key cannot `Ingest` data claiming to be tenant-b's (`INVALID_ARGUMENT`) - the gRPC-transport equivalent of `AuditLogServerMultiTenancyIntegrationTest` |

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
- **Message-queue-based server ingestion (Kafka/RabbitMQ consumer).** REST/gRPC only so far; a
  future MQ consumer would reuse `AuditLogRecorder` (WP12) and the same `.proto` schema.
- **A gRPC service on the same `.proto` schema.** Implemented in WP18 (`audit-log-spring-boot-grpc-server`) -
  no longer deferred. The schema was deliberately written so gRPC could reuse it without a rewrite;
  that's exactly what happened - every WP18 RPC request/response message is a message that already
  existed for REST.
- **Pre-built/published codegen packages for non-Java languages.** `docs/CLIENT_CODEGEN.md` makes
  self-service `protoc` codegen work (verified end-to-end for Python); publishing per-language
  packages to PyPI/npm/etc. is a packaging decision, not attempted.
- **Build-time (Maven-plugin) DB schema check.** Only the startup check (`AuditSchemaValidator`,
  WP10) exists; a build-time variant needs its own design (CI credentials, which phase to bind to).

### From WP15's out of scope - status after WP16

- **Per-tenant server authentication/authorization.** Implemented in WP16 (`ApiKeyAuthFilter` +
  `ApiKeyAuditTenantResolver`) - no longer deferred.
- **`AuditTemplate`/`AuditGroup` tenant-scoping.** Implemented in WP16 - no longer deferred.
- **Automatic per-tenant retention/purge scoping.** Implemented in WP16
  (`retention.tenant-max-age.<tenantId>`) - no longer deferred.
- **`AuditLogMessage` gains no `tenant_id`.** Still true, still deliberate: it's always looked up
  via `audit_log_id` from an already tenant-scoped `AuditLog` row (`JpaAuditLogQueryService` never
  queries it independently), so a tenant column there would be pure redundant denormalization
  today - revisit only if that access pattern changes.

### From WP16's out of scope

- **API key rotation/revocation.** `audit.log.server.api-keys.<tenantId>` is still a single static
  secret per tenant with no rotation story, expiry, or revocation list - a config change and
  restart is the only way to change one. Real rotation needs either a second "pending" key per
  tenant accepted alongside the active one during a rollover window, or delegating entirely to
  external authn/authz (mTLS, an OAuth2 resource server) as this module's README already
  recommends for production use.
- **A tenant provisioning/management API.** Tenants and their keys are pure configuration
  (`application.properties`/env vars/a config server) - there's no runtime endpoint to add, list,
  or remove a tenant's key. Appropriate for a config-managed deployment; a SaaS control plane
  wanting to self-serve tenant onboarding would need one.
- **Per-tenant rate limiting or quota.** Every authenticated tenant shares this module's ingest/
  query capacity equally; nothing here isolates one tenant's load from another's.

### From WP17's out of scope - status after WP18

- **A gRPC service on the same `.proto` schema.** Implemented in WP18 - no longer deferred (see
  above).

### From WP18's out of scope

- **A dedicated gRPC Java client wrapper module**, the gRPC equivalent of `audit-log-java-client`.
  Not built this pass - a caller needs the generated `AuditLogServiceGrpc` stub plus a small
  interceptor attaching the `x-api-key` metadata entry directly (`audit-log-spring-boot-grpc-server`'s
  own tests are a working example, via `io.grpc.stub.MetadataUtils`); there's no typed exception
  hierarchy or Boot auto-config equivalent to `AuditLogHttpClient`/`audit-log-java-client-spring-boot-starter`
  for gRPC yet.
- **TLS/mTLS for the gRPC server.** `AuditLogGrpcServer` builds a plaintext `ServerBuilder` -
  `x-api-key` metadata travels in cleartext unless the surrounding network is already encrypted
  (a service mesh, a load balancer terminating TLS). Same "front it with real infrastructure" caveat
  the REST server module's README already documents, just not yet a configurable server-side
  `SslContext` option here.
- **Server reflection / health-checking services** (`grpc.reflection.v1alpha.ServerReflection`,
  `grpc.health.v1.Health`) - common companion services for gRPC deployments (enabling `grpcurl`,
  k8s gRPC health probes) that `AuditLogGrpcServer` doesn't register. Would be an additive change
  if wanted (`.addService(ProtoReflectionService.newInstance())` etc.).

### From WP19's out of scope

- **A gRPC equivalent of the REST server's Swagger UI.** gRPC has no direct OpenAPI/Swagger
  analogue - `docs/CLIENT_CODEGEN.md`'s "gRPC clients" section and `audit_event.proto` itself remain
  the API reference for that protocol. `grpcurl`/server reflection (see "From WP18's out of scope")
  would be the natural next step if interactive gRPC exploration is wanted later.
- **Spec-vs-controller drift enforcement** for the OpenAPI spec - see "Known constraints" below.
- **Versioned/archived docs site.** `.github/workflows/pages.yml` always deploys the latest `main`
  to the one live site - there's no per-version snapshot (e.g. docs for an older published release)
  the way some projects offer; not needed yet since nothing has been published to Maven Central.

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
  pre-populated `~/.m2` cache. **(WP18)** now also downloads `protoc-gen-grpc-java` the same way,
  for the second codegen pass.
- **(WP18) Any consumer of `audit-log-spring-boot-starter` needs `jakarta.servlet-api` on the
  classpath, even for a pure-gRPC deployment with no MVC/Tomcat.** Not a WP18-specific bug - a
  latent, pre-existing requirement of the core starter's `DefaultAuditContextResolver` (see
  "Decisions" #18) that the REST server module always satisfied for free (transitively, via
  `spring-boot-starter-web`) and nothing surfaced until a module without that dependency was built.
  `audit-log-spring-boot-grpc-server` now supplies it directly so this is invisible to a gRPC-only
  host application, but it's worth knowing if a *third* protocol module is ever added the same way.
- **(WP18) `AuditLogGrpcServer` has no TLS/mTLS, server reflection, or health-check service** - see
  "From WP18's out of scope" above.
- **(WP19) `.github/workflows/pages.yml` cannot enable GitHub Pages itself.** Publishing a Pages
  site requires a one-time repository setting (Settings -> Pages -> "Build and deployment" ->
  Source: "GitHub Actions") that only a repo admin can set via the GitHub UI/API - no Maven/git
  action reaches it. Until that's set, the workflow runs and produces a build artifact, but the
  `deploy-pages` step fails against the Pages API. The workflow's header comment says this too.
- **(WP19) The REST server's OpenAPI spec has no automated drift check against the controllers** -
  see "Decisions" #19. A future addition worth considering: a test that deserializes the spec and
  asserts its paths/operations match `AuditIngestController`/`AuditQueryController`'s actual
  `@RequestMapping`s, to at least catch a removed/renamed endpoint the spec wasn't updated for.

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
