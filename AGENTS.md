# AGENTS.md

Spec file for any coding agent (Claude Code, Codex, Cursor, or otherwise) working in this
repository. Written as an evergreen reference, not a session log - it describes what the project
*is* and *how it's built*, not what happened in what order. For narrative history of specific past
work, see [`HANDOFF.md`](HANDOFF.md). If you're a human, [`README.md`](README.md) is the friendlier
starting point; this file is denser and optimized for an agent about to make a change.

## What this is

A Spring Boot starter (`audit-log`) that records audit trail entries for `@Audit`-annotated
methods via AspectJ, rendering messages from pluggable-source (properties or database) FreeMarker
templates. It also ships an optional REST server/client pair (Protobuf wire format) and an optional
gRPC server (WP18, same wire messages, same `AuditLogService` proto now also defining RPCs) for
callers with no in-process method invocation for AOP to intercept - a remote service, a non-JVM
caller. Package root: `io.github.bitaron.auditlog` (all-lowercase, deliberately - see
"Conventions" #13).

## Module map

```
pom.xml                                        aggregator, version 2.0.0-SNAPSHOT
audit_log/
  pom.xml                                      parent for the 7 modules below
  audit-log-spring-boot-autoconfigure/         ALL core implementation + tests + README
  audit-log-spring-boot-starter/               pom-only; what consumers of the core library depend on
  audit-log-server-proto/                       .proto IDL + generated Java stubs (Protobuf + gRPC), no Spring dependency
  audit-log-spring-boot-server/                 optional REST ingestion/query server, off by default
  audit-log-spring-boot-grpc-server/            (WP18) optional gRPC ingestion/query server, off by default
  audit-log-java-client/                        typed Java HTTP client for the server module, no Spring dependency
  audit-log-java-client-spring-boot-starter/    (WP17) opt-in Spring Boot auto-config for the client above
audit_log_usage_example/                        runnable demo app + integration test
audit_log_standalone_server/                    runnable standalone deployment of the REST server module
docs/
  SCALING.md                                    large-data operation: pagination, retention, partitioning
  CLIENT_CODEGEN.md                              generating a client for the REST server, any language
db/migration/V2__audit_log_v2.sql               1.x -> 2.x schema migration (PostgreSQL dialect)
db/migration/V3__audit_log_multi_tenancy.sql    adds the nullable, opt-in-enforced tenant_id column
db/migration/V4__audit_log_tenant_scoped_templates_groups.sql   tenant-scopes audit_template/audit_group
```

Dependency direction: `starter` -> `autoconfigure`. `server` -> `starter` (not `autoconfigure`
directly - see "Conventions" #12) + `server-proto`. `grpc-server` -> `starter` (same rationale as
`server`) + `server-proto` (WP18). `java-client` -> `server-proto` only.
`java-client-spring-boot-starter` -> `java-client` (a separate artifact specifically so a
non-Spring-Boot consumer of the plain client never gets `spring-boot-autoconfigure` forced onto its
classpath - see "Conventions" #17). The demo app -> `starter`. The standalone-server app ->
`audit-log-spring-boot-server`. `server` and `grpc-server` are never both depended on/enabled by the
same application - see "Conventions" #18. Never add a dependency that points the other way.

## Package/class map

### Core (`audit-log-spring-boot-autoconfigure`)

| Package | Key classes | Role |
|---|---|---|
| `annotation` | `Audit`, `ActorSource`, `AuditDeliveryMode`, `Audits`, `AuditIgnore` | The user-facing annotation and its attribute types |
| `autoconfigure` | `AuditLogAutoConfiguration` | Everything is wired here; every `@Bean` is `@ConditionalOnMissingBean` |
| | `AuditLogEntityScanRegistrar` | Adds this starter's entities to the host's entity scan *additively* |
| `contract` | `AuditTemplateSource`, `AuditLogTemplateResolver`, `AuditLogArgumentSerializer`, `AuditLogGenericDataGetter`, `AuditLogLocationResolver`, `AuditMetricsRecorder`, `AuditLogRecorder`, `AuditTenantResolver` | SPIs a consumer can implement to override defaults. **(WP17)** `AuditLogGenericDataGetter`'s 5 methods are now `default` (fallback to what `DefaultAuditContextResolver` already used) - override only what you need |
| `core` | `AuditLogAspect` | Single `@Around` advice, `@Order(LOWEST_PRECEDENCE - 1)` |
| | `AuditContextResolver` / `DefaultAuditContextResolver` | The **only** place that reads ambient request state |
| | `AuditLogger` | Delivery-mode dispatch (sync/async, per-call and global) |
| | `AuditLogWriter` | `@Transactional` persistence |
| | `AuditLogTaskExecutor` | Dedicated executor for async writes |
| | `AuditSchemaValidator` | Startup table-existence check |
| | `AuditLogRetentionService` | Opt-in scheduled/batched purge |
| | `DefaultAuditLogRecorder` | Non-AOP write path |
| | `FreemarkerTemplateResolver`, `JacksonAuditLogArgumentSerializer` | Default template/serialization implementations |
| | `DefaultAuditTenantResolver` | Header-based default tenant resolution, opt-in (see "Conventions" #14) |
| | `PropertiesAuditTemplateSource`, `DatabaseAuditTemplateSource` | Tenant-scoped (WP16) - a tenant-tagged template/row wins over the global one for that tenant, global as fallback |
| `model` | `AuditContext`, `AuditEventRequest` | Immutable data carriers through the write pipeline. **(WP17)** `AuditEventRequest.Builder` (via `AuditEventRequest.builder(auditType)`, plus `success(result)`/`failure(exception)` convenience methods keeping `result`/`exception`/`exceptionThrown` consistent) - the positional 17-arg constructor has several adjacent `String` params (`actorId`/`actorName`, `clientIp`/`clientLocation`) that are easy to transpose |
| `entity` | `AuditLog`, `AuditLogMessage`, `AuditOutcome`, `AuditTemplate`, `AuditGroup` | JPA entities - not the public read API, see `query`. `AuditTemplate`/`AuditGroup` are tenant-scoped via `GLOBAL_TENANT_ID = ""` (WP16 - see "Conventions" #15) |
| `query` | `AuditLogQueryService` / `JpaAuditLogQueryService`, `AuditQuery`, `AuditRecord`, `AuditCursor` | The supported read API. **(WP17)** `AuditQuery` gained static factories (`byActor`/`byType`/`byActorAndType`, alongside the existing `all()`) and immutable withers (`withActor`/`withType`/`withCreatedBetween`); `AuditRecord.toCursor()` is the named equivalent of `new AuditCursor(record.createdAt(), record.id())` for paging with `findAfter` |
| `properties` | `AuditLogProperties` | `@ConfigurationProperties("audit.log")`, nested `Headers`/`Executor`/`SchemaValidation`/`Query`/`Retention`/`MultiTenancy`; `Retention.tenantMaxAge` and a top-level `tenantTemplates` map are WP16 additions |
| `testsupport` (test-jar) | `AuditLogAssertions` | **(WP17)** Published as this module's `tests` classifier - `awaitRecord`/`awaitRecords` (poll `AuditLogQueryService` for `ASYNC`-delivered writes), `messagesFor`, `clearAuditLog`. Lets a consuming application's own tests assert through the documented read API instead of raw `EntityManager`/JPQL - see "Testing patterns" |

### Server/client (`audit-log-server-proto`, `audit-log-spring-boot-server`, `audit-log-spring-boot-grpc-server`, `audit-log-java-client`, `audit-log-java-client-spring-boot-starter`)

| Module | Key classes | Role |
|---|---|---|
| `audit-log-server-proto` | Generated from `audit_event.proto` | `AuditEventRequest`/`Response`, `AuditRecordProto`, `AuditQueryRequest`/`Response`, `AuditOutcomeProto`. **(WP17)** `AuditCursorQueryRequest`/`Response` for keyset pagination over the wire. **(WP18)** the same `.proto` file now also declares `service AuditLogService` (`Ingest`/`Query`/`QueryAfter`), so a second `protoc` codegen pass (`pluginId=grpc-java`) also produces `AuditLogServiceGrpc` (stub + `ImplBase`) alongside the plain message classes |
| `audit-log-spring-boot-server` | `AuditLogServerAutoConfiguration` | Gated by `audit.log.server.enabled` (default `false`); also requires `audit.log.multi-tenancy.enabled=true` (WP16). **(WP17)** now explicitly registers `AuditServerExceptionHandler` as a `@Bean` - see "Conventions" #17. **(WP18)** also fails startup if `audit.log.grpc.enabled=true` - see "Conventions" #18 |
| | `AuditIngestController` / `AuditQueryController` | `POST /audit-log/events`, `GET /audit-log/records`. **(WP17)** `AuditQueryController` also serves `GET /audit-log/records/after` (keyset pagination, mirrors `AuditLogQueryService#findAfter`) |
| | `ApiKeyAuthFilter` | Per-tenant (WP16): `X-API-Key` must match one of `audit.log.server.api-keys.<tenantId>`; resolves *which* tenant, not just whether the request is allowed |
| | `ApiKeyAuditTenantResolver` | WP16: the `AuditTenantResolver` this module registers - reads the tenant `ApiKeyAuthFilter` authenticated, not a client-suppliable header |
| | `ProtoMapper` | Wire<->domain mapping, one place |
| `audit-log-spring-boot-grpc-server` | `AuditLogGrpcServerAutoConfiguration` | **(WP18, new module)** Gated by `audit.log.grpc.enabled` (default `false`); also requires `audit.log.multi-tenancy.enabled=true` (same reasoning as the REST server module) and fails startup if `audit.log.server.enabled=true` - see "Conventions" #18 |
| | `AuditLogGrpcService` | `extends AuditLogServiceGrpc.AuditLogServiceImplBase` - implements `Ingest`/`Query`/`QueryAfter` on top of the same `AuditLogRecorder`/`AuditLogQueryService` beans; gRPC-`Status`-coded errors (`INVALID_ARGUMENT`/`INTERNAL`), mirroring `AuditServerExceptionHandler`'s REST-side error mapping |
| | `ApiKeyGrpcServerInterceptor` | `ServerInterceptor` reading the `x-api-key` gRPC metadata entry, resolving it against `audit.log.grpc.api-keys.<tenantId>`, and setting the authenticated tenant into a `io.grpc.Context` value - the gRPC-transport equivalent of `ApiKeyAuthFilter` |
| | `GrpcAuditTenantResolver` | The `AuditTenantResolver` this module registers - reads the tenant `ApiKeyGrpcServerInterceptor` put into `Context`, not a client-suppliable field |
| | `GrpcProtoMapper` | Wire<->domain mapping - a deliberate duplicate of `audit-log-spring-boot-server`'s `ProtoMapper` (not a shared dependency), documented in its own class javadoc, to avoid forcing Servlet/MVC types onto grpc-only deployments or vice versa |
| | `AuditLogGrpcServer` | Owns the `io.grpc.Server` lifecycle (`InitializingBean`/`DisposableBean`, same hand-rolled pattern as `AuditLogRetentionService`/`AuditLogTaskExecutor`), not `@Scheduled`/a `grpc-spring-boot-starter` dependency |
| `audit-log-java-client` | `AuditLogHttpClient` | Thin `RestClient` wrapper, no Spring Boot dependency - see its own README. **(WP17)** gained a `RestClient.Builder`-accepting constructor (the seam the starter module below injects a Boot-managed, timeout-configured builder through), `query(AuditQueryRequest)` (the existing 6-arg overload now delegates to it), and `queryAfter` for keyset pagination |
| | `exception.AuditLogClientException` + 4 subtypes | **(WP17)** `Authentication`/`BadRequest`/`Server`/`Connection` - translates `RestClient`'s generic response exceptions into typed ones a caller can actually branch on |
| | `AuditRecordProtos` | **(WP17)** `createdAt(AuditRecordProto)` - parses the raw ISO-8601 wire string back to a `LocalDateTime`, since the generated proto class can't be hand-edited to add the method itself |
| `audit-log-java-client-spring-boot-starter` | `AuditLogClientAutoConfiguration` | **(WP17, new module)** Gated by `audit.log.client.enabled` (default `false`); registers an `AuditLogHttpClient` bean from `audit.log.client.base-url`/`api-key`/`http.connect-timeout`/`http.read-timeout`. No gRPC equivalent client module yet - see `audit-log-spring-boot-grpc-server/README.md`'s "Client options" |
| `audit_log_standalone_server` | `AuditLogServerApplication` | Runnable standalone deployment of the REST server - H2 by default, `postgres` profile available; requires at least one `audit.log.server.api-keys.<tenantId>` entry supplied externally at startup (see "Build & test") |

## Build & test

Requires **JDK 21**, not 25 (Lombok doesn't yet generate members correctly under JDK 25's compiler
internals - see the comment in the root `pom.xml`).

```bash
mvn clean install                                                # everything, from repo root

# Path 1: library jars only (the 5 audit-log-* modules) - installable/publishable, for use as a
# dependency in any Spring Boot app. Scoped to audit_log/pom.xml's own <modules>, so it never
# touches either runnable app below.
mvn -f audit_log/pom.xml clean install
mvn -f audit_log/pom.xml clean deploy -P release                   # actual publish - existing
                                                                     # OSSRH + GPG release profile

mvn -pl audit_log/audit-log-spring-boot-autoconfigure test        # core module only
mvn -pl audit_log/audit-log-spring-boot-server test                # REST server module only
mvn -pl audit_log/audit-log-spring-boot-grpc-server test           # gRPC server module only (WP18;
                                                                     # starts a real gRPC server at an
                                                                     # ephemeral port, audit.log.grpc.port=0,
                                                                     # to test against)
mvn -pl audit_log/audit-log-java-client test                       # client module only (starts a
                                                                     # real embedded server at a
                                                                     # random port to test against)
mvn -pl audit_log/audit-log-java-client-spring-boot-starter test   # client auto-config module only
cd audit_log_usage_example && mvn spring-boot:run                   # runnable demo, localhost:8080

# Path 2: run the REST server standalone. H2 in-memory by default; at least one
# audit.log.server.api-keys.<tenantId> entry has no default (fails fast at startup if none is
# configured - see AuditLogServerAutoConfiguration, which also requires
# audit.log.multi-tenancy.enabled=true, WP16) and must be supplied externally. Spring's relaxed
# env-var binding reliably maps a Map<String,String> key only when the key itself has no
# dashes/dots - "default" (this module's out-of-the-box tenant id) has none:
AUDIT_LOG_SERVER_APIKEYS_DEFAULT=<your-secret> mvn -f audit_log_standalone_server/pom.xml spring-boot:run

# ...or against Postgres instead (docker-compose.yml lives in that module's directory):
cd audit_log_standalone_server && docker compose up -d
AUDIT_LOG_SERVER_APIKEYS_DEFAULT=<your-secret> mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

`audit-log-server-proto` downloads a `protoc` binary (`os-maven-plugin` + `protobuf-maven-plugin`)
on first build - needs outbound access to Maven Central or a mirror.

## Configuration reference (`audit.log.*` / `audit.log.server.*`)

| Property | Default | Purpose |
|---|---|---|
| `audit.log.enabled` | `true` | Master switch for the core starter |
| `audit.log.mode` | `ASYNC` | Global delivery mode (`ASYNC`/`SYNC`); overridable per call via `@Audit(mode=...)` |
| `audit.log.headers.requester-id` / `requester-name` | `X-USER-ID` / `X-USER-NAME` | Actor headers when no `AuditLogGenericDataGetter` is configured |
| `audit.log.trust-forwarded-headers` | `false` | Trust `X-Forwarded-For`-style headers for client IP |
| `audit.log.masked-fields` | `password, secret, token, authorization, creditCardNumber` | Fields redacted in the `data` JSON |
| `audit.log.max-serialized-data-length` | `8192` | Truncation threshold for `data` |
| `audit.log.max-template-cache-size` | `256` | LRU-bounded compiled-template cache |
| `audit.log.templates.<name>` | - | Define a template in config instead of the `audit_template` table |
| `audit.log.fail-on-missing-template` | `false` | Fail startup instead of warning per-call on an unresolvable template |
| `audit.log.executor.core-pool-size` / `max-pool-size` / `queue-capacity` / `await-termination-seconds` | `2` / `10` / `500` / `30` | Async-dispatch executor sizing and shutdown grace period |
| `audit.log.schema-validation.enabled` | `true` | Startup check that the 4 required tables exist |
| `audit.log.query.max-page-size` | `200` | Max page size `find`/`findAfter` accept |
| `audit.log.retention.enabled` | `false` | Master switch for the scheduled purge job |
| `audit.log.retention.max-age` | *(required if enabled)* | Records older than this are eligible for deletion |
| `audit.log.retention.cron` | `0 0 3 * * *` | Purge job schedule |
| `audit.log.retention.batch-size` | `1000` | Rows deleted per batch iteration |
| `audit.log.multi-tenancy.enabled` | `false` | Master switch for tenant tagging/scoping - see "Conventions" #14 |
| `audit.log.headers.tenant-id` | `X-TENANT-ID` | Header the default `AuditTenantResolver` reads from |
| `audit.log.tenant-templates.<tenantId>.<name>` | - | Per-tenant template override (WP16), tried before `templates.<name>` for that tenant |
| `audit.log.retention.tenant-max-age.<tenantId>` | - | Per-tenant retention window override (WP16); falls back to `retention.max-age` |
| `audit.log.server.enabled` | `false` | Master switch for the REST server module; requires `audit.log.multi-tenancy.enabled=true` (WP16) |
| `audit.log.server.api-keys.<tenantId>` | *(at least one required if enabled)* | Per-tenant API key (WP16) - which tenant a request acts as is authenticated by which key it presents |
| `audit.log.client.enabled` | `false` | **(WP17)** Master switch for the auto-registered `AuditLogHttpClient` bean (`audit-log-java-client-spring-boot-starter`) |
| `audit.log.client.base-url` | *(required if enabled)* | The REST server module's base URL |
| `audit.log.client.api-key` | - | The value configured as one of `audit.log.server.api-keys.<tenantId>` on the server - determines which tenant this client acts as |
| `audit.log.client.http.connect-timeout` / `read-timeout` | `5s` / `30s` | Timeouts for every request this client makes |
| `audit.log.grpc.enabled` | `false` | **(WP18)** Master switch for the gRPC server module; requires `audit.log.multi-tenancy.enabled=true` and cannot coexist with `audit.log.server.enabled=true` in the same application - see "Conventions" #18 |
| `audit.log.grpc.port` | `9090` | Port the gRPC server listens on; `0` binds an OS-assigned ephemeral port (used by this module's own tests) |
| `audit.log.grpc.api-keys.<tenantId>` | *(at least one required if enabled)* | Per-tenant API key, presented via the `x-api-key` gRPC metadata entry - same authentication model as `audit.log.server.api-keys.<tenantId>`, own separate namespace |

Full property javadoc lives on `AuditLogProperties`/`AuditLogServerProperties`/
`AuditLogClientProperties`/`AuditLogGrpcServerProperties` themselves - this table is for discovery,
not the last word on behavior.

## Conventions and load-bearing decisions - do not "simplify" these

Each was chosen against an obvious-looking alternative that is actually wrong. Reverting one
reintroduces a real, previously-fixed bug. Full rationale for each is in `HANDOFF.md`; summarized
here for fast lookup.

1. **No `@Bean EntityManager`.** Every internal consumer builds its own shared-EntityManager proxy
   from `EntityManagerFactory` via a private `sharedEntityManager(...)` helper in
   `AuditLogAutoConfiguration`. Adding a plain `EntityManager` bean would let a host app's own
   unqualified `@Autowired EntityManager` silently resolve this starter's instance instead of
   failing loudly, and could introduce ambiguity in a multi-persistence-unit host.
2. **`jakarta.validation-api` is `provided` scope, not compile**, in the autoconfigure module's pom.
   Spring Boot's binder throws `NoProviderFoundException` if the validation API is visible on the
   classpath with no implementation present - making it transitive would break every consumer
   without `spring-boot-starter-validation`.
3. **`AuditLogAspect` is `@Order(Ordered.LOWEST_PRECEDENCE - 1)`** - one step ahead of the default
   `@Transactional` advisor order, so it deterministically wraps *outside* transactional advice.
4. **`AuditLogWriter` is a separate bean from `AuditLogger`.** Self-invocation would bypass the
   `@Transactional` proxy. `AuditLogRetentionService` applies the same reasoning via
   `TransactionTemplate` instead of a `@Transactional` method on itself.
5. **No `@Async`/`@EnableAsync`, no `@Scheduled`/`@EnableScheduling`.** Both are context-wide,
   consumer-visible behavior changes a library must not impose. `AuditLogTaskExecutor` and
   `AuditLogRetentionService` each own a dedicated executor/scheduler instead.
6. **`AuditLogEntityScanRegistrar` adds to, never replaces, the host's entity scan** - reads the
   host's existing `AutoConfigurationPackages` registration and folds this starter's package in.
7. **Repeated `@Audit` is fully processed.** `AuditLogAspect`'s pointcut matches both `@Audit` and
   the synthetic `@Audits` container (`@annotation(Audit) || @annotation(Audits)`) without binding
   either as a parameter; the advice resolves every instance via
   `AnnotatedElementUtils.findMergedRepeatableAnnotations` and dispatches one independently-
   isolated record per instance. Binding a single `Audit actLog` parameter on the pointcut again
   only fires the first of several stacked annotations - that was a real, fixed bug.
8. **`AuditDeliveryMode` (the `@Audit#mode()` type) is a distinct enum from
   `AuditLogProperties.DeliveryMode`**, not a reuse - the annotation shouldn't depend on the
   properties package, and `INHERIT` is meaningless for the global property itself.
9. **`AuditSchemaValidator` probes via raw JDBC** (a fresh `Connection` per table), never JPA - a
   `PersistenceException` from one missing-table probe would poison that `EntityManager`'s
   transaction for every table checked afterward.
10. **`JpaAuditLogQueryService.find`'s sort is whitelisted** to `id`/`createdAt`/`actorId`/
    `auditType` - the only indexed/filterable columns. Accepting arbitrary sort properties would
    both build unvalidated JPQL from caller input and force full sorts on unindexed columns.
11. **`AuditLogRecorder`'s implementation synthesizes a real `Audit` annotation instance** via
    `AnnotationUtils.synthesizeAnnotation` (a dynamic proxy) rather than duplicating
    `AuditLogWriter`/`AuditLogger`'s dispatch logic for a caller with no join point. Verified
    (`AuditLogRecorderTest`) to produce the identical `AuditLog`/`AuditLogMessage` shape as the real
    `@Audit` + AOP path.
12. **`audit-log-spring-boot-server` depends on `audit-log-spring-boot-starter`, not
    `audit-log-spring-boot-autoconfigure` directly.** `AuditLogAutoConfiguration` always attempts
    to register an `AuditLogAspect` bean, and that bean's class references AspectJ types at the
    bytecode level - loading it without `spring-boot-starter-aop` on the runtime classpath throws
    `NoClassDefFoundError`, not a graceful skip. Caught empirically while building the server module.
13. **`ProtobufHttpMessageConverter`'s JSON support needs `protobuf-java-util`** (for
    `com.google.protobuf.util.JsonFormat`) on the classpath - a runtime classpath probe, not a
    declared dependency of the converter class. Without it, JSON requests to the server module
    silently get `415`, not an error naming what's missing. Also caught empirically.
14. **`AuditTenantResolver` is its own SPI, not a method on `AuditLogGenericDataGetter`,** and
    `JpaAuditLogQueryService` enforces tenant scoping internally rather than accepting a
    caller-suppliable `tenantId` on `AuditQuery`. Tenant identity is orthogonal to actor identity
    (a `SYSTEM`-actor scheduled job still runs on behalf of one tenant), so it's resolved
    unconditionally, not nested in `DefaultAuditContextResolver`'s `actorSource` switch. And a
    caller-suppliable read-side filter would be exactly the field a future caller forgets to pass -
    putting the mandatory predicate inside the query service itself, resolved fresh per call and
    failing closed when unresolvable, is what makes cross-tenant leakage structurally hard rather
    than merely discouraged.
15. **`AuditTemplate`/`AuditGroup.tenantId` uses `""` (empty string), never `null`, as its
    "not tenant-specific" sentinel** - deliberately different from `AuditLog.tenantId`'s
    "`null` = default tenant" convention (Conventions #14's neighbor). Both tables have a real
    composite `(tenant_id, name)` unique constraint, and standard SQL treats every `NULL` as
    distinct for uniqueness purposes - a `NULL`-based convention here would silently allow
    duplicate global template/group names. `AuditTemplate.GLOBAL_TENANT_ID`/
    `AuditGroup.GLOBAL_TENANT_ID` are the named constant; `AuditLogWriter` normalizes a `null`
    `AuditContext.tenantId()` to it before querying/persisting either entity. Also has a
    `columnDefinition` (not just `nullable = false`) on the mapped column, so a `ddl-auto`-
    generated schema gets a real SQL-level `DEFAULT ''` too - otherwise a raw `INSERT` that omits
    the column (any seed-data script) would hit a `NOT NULL` violation instead of picking up the
    sentinel. Caught empirically fixing `audit_log_usage_example`'s `data.sql`.
16. **The server module's per-tenant API keys (`ApiKeyAuthFilter`) authenticate a tenant, not just
    tag one.** `AuditIngestController` derives the persisted event's tenant from
    `AuditTenantResolver` (i.e. from the key), never trusting the wire `tenant_id` on its own -
    a body naming a *different* tenant than the one authenticated is rejected (`400`), not
    silently overridden. `AuditLogServerAutoConfiguration` fails startup if
    `audit.log.multi-tenancy.enabled` isn't also `true`: per-tenant keys without the core
    starter's tenant-scoped read enforcement would authenticate a tenant identity that nothing
    then confines reads by, which would be misleading rather than merely incomplete.
17. **`AuditServerExceptionHandler` (`@RestControllerAdvice`) is registered explicitly as a
    `@Bean` in `AuditLogServerAutoConfiguration` (WP17), not left to component scanning.**
    `@RestControllerAdvice` is a scanning stereotype - whether it was ever picked up depended
    entirely on whether the host application's `@SpringBootApplication` base package happened to
    cover `io.github.bitaron.auditlog.server`. Every one of this module's own tests had a test
    application that either did (passing by coincidence) or never exercised a caller-error path at
    all, so this went undetected until `AuditLogHttpClientErrorHandlingTest` (client exception
    hierarchy work) hit it directly: a real host application's base package essentially never
    overlaps with this library's, so every `IllegalArgumentException`/`IllegalStateException` the
    controllers throw silently surfaced as an unhelpful `500` instead of the documented `400`.
    Caught empirically - explicit `@Bean` registration is required for every stereotype-annotated
    class this starter ships, not just the ones already written that way.
18. **`audit-log-spring-boot-server` (REST) and `audit-log-spring-boot-grpc-server` (gRPC, WP18)
    fail startup loudly if both are enabled in the same application**, rather than silently letting
    one win. Each authenticates its per-tenant API keys into a different, non-interoperable
    request-scoped context - an `HttpServletRequest` attribute for REST
    (`ApiKeyAuditTenantResolver`), a gRPC `Context` value for gRPC (`GrpcAuditTenantResolver`) - and
    only one `AuditTenantResolver` bean can be active application-wide. Whichever module's
    `@AutoConfigureBefore(AuditLogAutoConfiguration.class)` autoconfiguration processes first would
    silently win the `@ConditionalOnMissingBean` race for that bean, leaving the other protocol's
    calls resolving no tenant at all - an order-dependent latent bug rather than an obvious failure.
    Each module's own `Environment.getProperty("audit.log.<other>.enabled", Boolean.class, false)`
    check (no compile dependency between the two sibling optional modules) catches this at startup
    instead. Deploy REST and gRPC as separate application instances if you need both protocols.

## Testing patterns

- **`ApplicationContextRunner` + a marker class** (`HostAppMarker` in the autoconfigure module's
  tests, `ServerHostAppMarker` in the server module's - not shared, since test sources aren't
  published as a dependency) simulates a host application relying on Spring Boot's implicit default
  entity/repository scanning - the common case this starter must never break. Used throughout
  `AuditLogAutoConfigurationTest`, `AuditLogServerAutoConfigurationTest`, `AuditSchemaValidatorTest`,
  `JpaAuditLogQueryServiceTest`, `AuditLogRetentionServiceTest`.
- **Real-AOP tests** (`AuditLogAspectTest`, `AuditLogAspectTransactionOrderingTest`,
  `AuditLogRecorderTest`) add `AopAutoConfiguration` to the context runner and go through an actual
  woven proxy - required whenever the pointcut expression or advice ordering itself is what's being
  tested, not just the downstream dispatch logic.
- **Mock-based unit tests** (`AuditLoggerTest`) use a directly-executing `Executor` and Mockito
  mocks to drive commit-deferral precisely via `TransactionSynchronizationManager`, without a real
  transaction manager or database.
- **Seeding rows with explicit `createdAt`** bypasses `AuditLogWriter` (which always stamps
  `now()`) by persisting `AuditLog`/`AuditLogMessage` entities directly inside a
  `TransactionTemplate` block - see `JpaAuditLogQueryServiceTest.seedRows` and
  `AuditLogRetentionServiceTest.seedAuditLog` for the pattern, needed whenever a test needs to
  control the time dimension (pagination ordering, retention cutoffs).
- **`Awaitility`** (already on the test classpath via `spring-boot-starter-test`) is how every test
  observing an `ASYNC`-dispatched write waits for it, rather than a fixed `Thread.sleep`.
- **`@SpringBootTest` + `MockMvc`/`RestClient` at a random port** (`AuditLogServerIntegrationTest`,
  `AuditLogHttpClientTest`) is reserved for the server/client modules, where a real HTTP round trip
  (content negotiation, the API-key filter) is the thing under test. WP16 split the per-tenant-key
  cross-tenant-isolation cases into their own `AuditLogServerMultiTenancyIntegrationTest` class,
  since it needs more than one configured tenant while `AuditLogServerIntegrationTest` deliberately
  keeps testing the single-tenant case.
- **`@SpringBootTest` + a real `ManagedChannel` at an ephemeral port** (WP18,
  `AuditLogGrpcServerIntegrationTest`/`AuditLogGrpcServerMultiTenancyIntegrationTest`) is the gRPC
  equivalent - `audit.log.grpc.port=0` so `AuditLogGrpcServer.getPort()` returns the real OS-assigned
  port to build the channel against, `io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor` to
  attach the `x-api-key` metadata entry per call, and asserting on
  `StatusRuntimeException.getStatus().getCode()` rather than an HTTP status code. Same
  single-tenant/multi-tenant test-class split as the REST module, for the same reason.

## Where to go deeper

- [`MIGRATION.md`](MIGRATION.md) - full 1.x -> 2.x API/schema mapping, plus everything added since
  the initial 2.0.0-SNAPSHOT (per-call delivery mode, schema validation, pagination, retention,
  server mode, client codegen) with the exact new config properties.
- [`docs/SCALING.md`](docs/SCALING.md) - large-data operation: offset vs. keyset pagination, the
  Hibernate `IDENTITY`-batching limitation (and why there's no runtime property to switch it),
  retention, table partitioning.
- [`docs/CLIENT_CODEGEN.md`](docs/CLIENT_CODEGEN.md) - generating a client for the REST server (or,
  as of WP18, a gRPC client via the `grpc-java` `protoc` plugin) in any language directly from
  `audit_event.proto`, plus the schema compatibility rules to follow when changing it.
- [`HANDOFF.md`](HANDOFF.md) - narrative record of what was built, in what order, and why, across
  both the v2 redesign and the v3 (server mode/scale) pass. Read this if you want the *history*
  behind a decision, not just the decision itself.
- Each module's own `README.md` (`audit_log/audit-log-spring-boot-autoconfigure/README.md` is the
  primary one - usage examples, extension points, the actor-identity trust model;
  `audit_log/audit-log-spring-boot-server/README.md` covers the REST endpoints and per-tenant
  authentication specifically; `audit_log/audit-log-spring-boot-grpc-server/README.md` (WP18)
  covers the equivalent gRPC RPCs, and the REST/gRPC mutual-exclusion constraint, specifically;
  `audit_log/audit-log-java-client/README.md` and
  `audit_log/audit-log-java-client-spring-boot-starter/README.md` (WP17) cover the typed HTTP
  client and its optional Boot auto-config).

## Contribution conventions

- Commit messages are imperative, explain **why** a change was made (not just what changed), and
  call out anything empirically discovered while making it (a gotcha, a test that caught a real
  bug) - match the style already in this repo's `git log`, not a generic "update X" one-liner.
- One logical unit of work per commit; this repo's history is organized as one commit per
  work-package (WP0, WP1, ... WP14) from its two major passes - keep that granularity for anything
  comparably sized.
- Never force-push over commits that aren't yet merged into `main` unless explicitly asked to.
- `mvn clean install` must be green before considering any change done - this repo has no
  build-server-only test dependencies; what passes locally is what CI runs
  (`.github/workflows/build.yml`).
