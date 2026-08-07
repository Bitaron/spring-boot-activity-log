# AGENTS.md

Spec file for any coding agent (Claude Code, Codex, Cursor, or otherwise) working in this
repository. Written as an evergreen reference, not a session log - it describes what the project
*is* and *how it's built*, not what happened in what order. For narrative history of specific past
work, see [`HANDOFF.md`](HANDOFF.md). If you're a human, [`README.md`](README.md) is the friendlier
starting point; this file is denser and optimized for an agent about to make a change.

## What this is

A Spring Boot starter (`audit-log`) that records audit trail entries for `@Audit`-annotated
methods via AspectJ, rendering messages from pluggable-source (properties or database) FreeMarker
templates. It also ships an optional REST server/client pair (Protobuf wire format) for callers
with no in-process method invocation for AOP to intercept - a remote service, a non-JVM caller.
Package root: `io.github.bitaron.auditlog` (all-lowercase, deliberately - see "Conventions" #13).

## Module map

```
pom.xml                                        aggregator, version 2.0.0-SNAPSHOT
audit_log/
  pom.xml                                      parent for the 5 modules below
  audit-log-spring-boot-autoconfigure/         ALL core implementation + tests + README
  audit-log-spring-boot-starter/               pom-only; what consumers of the core library depend on
  audit-log-server-proto/                       .proto IDL + generated Java stubs, no Spring dependency
  audit-log-spring-boot-server/                 optional REST ingestion/query server, off by default
  audit-log-java-client/                        typed Java HTTP client for the server module
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
directly - see "Conventions" #12) + `server-proto`. `java-client` -> `server-proto` only. The demo
app -> `starter`. The standalone-server app -> `audit-log-spring-boot-server`. Never add a
dependency that points the other way.

## Package/class map

### Core (`audit-log-spring-boot-autoconfigure`)

| Package | Key classes | Role |
|---|---|---|
| `annotation` | `Audit`, `ActorSource`, `AuditDeliveryMode`, `Audits`, `AuditIgnore` | The user-facing annotation and its attribute types |
| `autoconfigure` | `AuditLogAutoConfiguration` | Everything is wired here; every `@Bean` is `@ConditionalOnMissingBean` |
| | `AuditLogEntityScanRegistrar` | Adds this starter's entities to the host's entity scan *additively* |
| `contract` | `AuditTemplateSource`, `AuditLogTemplateResolver`, `AuditLogArgumentSerializer`, `AuditLogGenericDataGetter`, `AuditLogLocationResolver`, `AuditMetricsRecorder`, `AuditLogRecorder`, `AuditTenantResolver` | SPIs a consumer can implement to override defaults |
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
| `model` | `AuditContext`, `AuditEventRequest` | Immutable data carriers through the write pipeline |
| `entity` | `AuditLog`, `AuditLogMessage`, `AuditOutcome`, `AuditTemplate`, `AuditGroup` | JPA entities - not the public read API, see `query`. `AuditTemplate`/`AuditGroup` are tenant-scoped via `GLOBAL_TENANT_ID = ""` (WP16 - see "Conventions" #15) |
| `query` | `AuditLogQueryService` / `JpaAuditLogQueryService`, `AuditQuery`, `AuditRecord`, `AuditCursor` | The supported read API |
| `properties` | `AuditLogProperties` | `@ConfigurationProperties("audit.log")`, nested `Headers`/`Executor`/`SchemaValidation`/`Query`/`Retention`/`MultiTenancy`; `Retention.tenantMaxAge` and a top-level `tenantTemplates` map are WP16 additions |

### Server/client (`audit-log-server-proto`, `audit-log-spring-boot-server`, `audit-log-java-client`)

| Module | Key classes | Role |
|---|---|---|
| `audit-log-server-proto` | Generated from `audit_event.proto` | `AuditEventRequest`/`Response`, `AuditRecordProto`, `AuditQueryRequest`/`Response`, `AuditOutcomeProto` |
| `audit-log-spring-boot-server` | `AuditLogServerAutoConfiguration` | Gated by `audit.log.server.enabled` (default `false`); also requires `audit.log.multi-tenancy.enabled=true` (WP16) |
| | `AuditIngestController` / `AuditQueryController` | `POST /audit-log/events`, `GET /audit-log/records` |
| | `ApiKeyAuthFilter` | Per-tenant (WP16): `X-API-Key` must match one of `audit.log.server.api-keys.<tenantId>`; resolves *which* tenant, not just whether the request is allowed |
| | `ApiKeyAuditTenantResolver` | WP16: the `AuditTenantResolver` this module registers - reads the tenant `ApiKeyAuthFilter` authenticated, not a client-suppliable header |
| | `ProtoMapper` | Wire<->domain mapping, one place |
| `audit-log-java-client` | `AuditLogHttpClient` | Thin `RestClient` wrapper |
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
mvn -pl audit_log/audit-log-java-client test                       # client module only (starts a
                                                                     # real embedded server at a
                                                                     # random port to test against)
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

Full property javadoc lives on `AuditLogProperties`/`AuditLogServerProperties` themselves - this
table is for discovery, not the last word on behavior.

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

## Where to go deeper

- [`MIGRATION.md`](MIGRATION.md) - full 1.x -> 2.x API/schema mapping, plus everything added since
  the initial 2.0.0-SNAPSHOT (per-call delivery mode, schema validation, pagination, retention,
  server mode, client codegen) with the exact new config properties.
- [`docs/SCALING.md`](docs/SCALING.md) - large-data operation: offset vs. keyset pagination, the
  Hibernate `IDENTITY`-batching limitation (and why there's no runtime property to switch it),
  retention, table partitioning.
- [`docs/CLIENT_CODEGEN.md`](docs/CLIENT_CODEGEN.md) - generating a client for the REST server in
  any language directly from `audit_event.proto`, plus the schema compatibility rules to follow
  when changing it.
- [`HANDOFF.md`](HANDOFF.md) - narrative record of what was built, in what order, and why, across
  both the v2 redesign and the v3 (server mode/scale) pass. Read this if you want the *history*
  behind a decision, not just the decision itself.
- Each module's own `README.md` (`audit_log/audit-log-spring-boot-autoconfigure/README.md` is the
  primary one - usage examples, extension points, the actor-identity trust model;
  `audit_log/audit-log-spring-boot-server/README.md` covers the REST endpoints and per-tenant
  authentication specifically).

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
