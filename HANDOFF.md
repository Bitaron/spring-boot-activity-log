# Handoff: audit-log 2.0 redesign

Status as of the last commit on `claude/project-audit-planning-ztbevf`. Written so another agent
(or human) can pick this up cold. For the *user-facing* API mapping see
[`MIGRATION.md`](MIGRATION.md); this document is about the state of the work itself.

## TL;DR

The v2 redesign is **complete and merged to `main`**. `mvn clean verify` is green across all
modules: 45 tests in the starter, 2 in the demo app. There is no in-flight work and no known
broken state. What remains is all optional/deferred - see "Not done (deliberately)" below.

## How to verify you're in a good state

```bash
mvn clean verify                                        # all modules, must be green
mvn -pl audit_log/audit-log-spring-boot-autoconfigure test   # the starter's 45 tests
cd audit_log_usage_example && mvn spring-boot:run       # then curl localhost:8080/test
```

Requires **JDK 21** (not 25 - see "Known constraints").

## Repository layout

```
pom.xml                                        aggregator (version 2.0.0-SNAPSHOT)
MIGRATION.md                                   1.x -> 2.x API + schema mapping
db/migration/V2__audit_log_v2.sql              1.x -> 2.x schema migration (PostgreSQL dialect)
audit_log/
  pom.xml                                      parent for the two starter modules
  audit-log-spring-boot-autoconfigure/         ALL implementation code + tests + README
  audit-log-spring-boot-starter/               pom-only aggregator; what consumers depend on
audit_log_usage_example/                       runnable demo app + integration test
```

Package root: `io.github.bitaron.auditlog` (all-lowercase - it was `auditLog` in 1.x).

## What each class is for

| Package | Class | Role |
|---|---|---|
| `annotation` | `Audit` | The user-facing annotation. `templates()`, `actorSource()`, `actorExpression()`, `auditType()`, `actionName()`, `actionType()`, `groupName()` |
| | `ActorSource` | `CONTEXT` / `SYSTEM` / `EXPRESSION` - replaced the 1.x `isActorSystem`/`isActorCommon` booleans |
| | `Audits` | `@Repeatable` container for `Audit` (see "Known limitations") |
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
| `core` | `AuditLogAspect` | Single `@Around` advice, `@Order(LOWEST_PRECEDENCE - 1)`. Captures args/result/exception/duration, evaluates `actorExpression` SpEL (cached per `Method`) |
| | `AuditContextResolver` / `DefaultAuditContextResolver` | The **only** place that reads ambient request state |
| | `AuditLogger` | Delivery-mode dispatch: SYNC direct, ASYNC deferred to `afterCommit` when a tx is active |
| | `AuditLogWriter` | `@Transactional` persistence. Two entry points (`persistRequiresNew` / `persistShared`) - separate bean from `AuditLogger` so the proxy is actually invoked |
| | `AuditLogTaskExecutor` | Dedicated pool; graceful shutdown accounting + MDC propagation |
| | `AuditTemplateValidator` | Opt-in startup validation of `@Audit(templates=...)` |
| | `FreemarkerTemplateResolver` | Default renderer; LRU-bounded compiled-template cache, `?api` disabled, `SAFER_RESOLVER` |
| | `JacksonAuditLogArgumentSerializer` | Default serializer; placeholders, masking, valid-JSON truncation |
| `model` | `AuditContext` | Immutable record passed through the whole pipeline |
| `entity` | `AuditLog` | One row per invocation. `@Immutable`, id-based equals/hashCode |
| | `AuditLogMessage` | Child rows: one per rendered template, keyed by `templateName` |
| | `AuditOutcome` | `SUCCESS` / `FAILURE` |
| `query` | `AuditLogQueryService` / `JpaAuditLogQueryService` | The supported read API |
| | `AuditQuery` / `AuditRecord` | Filter + immutable projection |
| `properties` | `AuditLogProperties` | `@Validated @ConfigurationProperties("audit.log")`, nested `Headers` and `Executor` |

## Decisions that are load-bearing - do not "simplify" these

Each of these was chosen against an obvious-looking alternative that is actually wrong. Changing
one back will reintroduce a real bug.

1. **`AuditLogAutoConfiguration` never registers an `EntityManager`-typed bean.** Internal
   consumers each build their own shared-EntityManager proxy from `EntityManagerFactory` via the
   private `sharedEntityManager(...)` helper. Both `@Bean(defaultCandidate = false)` and
   `@Bean(autowireCandidate = false)` were tried and **empirically fail**: `defaultCandidate` only
   de-prioritizes among multiple same-type candidates (a no-op when ours is the only one -
   the common case), and `autowireCandidate` also blocks the starter's own qualified injection.
   Guarded by `starterAddsNoEntityManagerTypedBeanToTheHostContext`.

2. **`jakarta.validation-api` is `provided` scope, not compile.** Spring Boot's
   `ConfigurationPropertiesBinder` tries to build a JSR-303 validator whenever
   `jakarta.validation.Validator` is *visible on the classpath* and throws
   `NoProviderFoundException` if no implementation is present. Making it transitive would break
   every consumer that doesn't happen to depend on `spring-boot-starter-validation`. This was
   caught by the demo app failing during WP6.

3. **`AuditLogAspect` is `@Order(Ordered.LOWEST_PRECEDENCE - 1)`.** One step ahead of the default
   `@Transactional` advisor order, so it deterministically wraps *outside* transactional advice
   rather than tying at the same order (undefined). Verified end-to-end through a real AOP proxy
   by `AuditLogAspectTransactionOrderingTest`.

4. **`AuditLogWriter` is a separate bean from `AuditLogger`.** Self-invocation would bypass the
   `@Transactional` proxy entirely.

5. **The executor is not `@Async`/`@EnableAsync`.** Turning on async proxying is a context-wide,
   consumer-visible change a library shouldn't impose.

6. **`AuditLogEntityScanRegistrar` adds to - never replaces - the host's entity scan.** The
   original 1.x `@EntityScan` silently broke host entity discovery. Guarded by
   `hostApplicationOwnEntityAndRepositoryStillDiscovered`.

7. **`@Inherited` on both `Audit` and `Audits`.** javac rejects a `@Repeatable` annotation being
   `@Inherited` when its container isn't.

## Test inventory (what's actually guarded)

| Test | Guards |
|---|---|
| `AuditLogAutoConfigurationTest` | Bean registration, `audit.log.enabled=false`, user overrides, **host entity/repository discovery**, **no `EntityManager` bean added**, **`AutoConfiguration.imports` names a loadable class**, properties-only templates, `fail-on-missing-template` both ways, executor-size validation, query service |
| `AuditLogWriterTest` | Rendering, missing/broken templates, one-row-per-event with N messages, `duration_ms`, `data` excludes actor fields, masking, servlet-arg safety, **rollback leaves zero rows (ASYNC + SYNC)**, commit persists |
| `AuditLoggerTest` | Dispatch mode matrix, commit deferral, rollback never dispatches, failure/rejection counted not propagated |
| `AuditLogAspectTransactionOrderingTest` | **Real `@Transactional @Audit` proxy**: business write rolls back, audit still records `FAILURE`; commit path records `SUCCESS` |
| `AuditLogTaskExecutorTest` | Shutdown accounting, MDC propagation |
| `FreemarkerTemplateResolverTest` | Rendering, cache reuse, bounded cache, `?api` blocked |
| `JacksonAuditLogArgumentSerializerTest` | Placeholders, `Throwable` compaction, deep masking, valid-JSON truncation |
| `AuditLogTestControllerIntegrationTest` (demo) | Full stack via MockMvc: outcome, duration, child messages, exception propagation |

## Not done (deliberately - from the plan's "out of scope")

- **Transactional outbox / guaranteed delivery.** `audit.log.mode=SYNC` covers the compliance case.
- **Replacing FreeMarker with SpEL for message bodies.** SpEL is only used for `actorExpression`.
- **Publishing 2.0.0 to Maven Central.** The `release` profile exists but needs GPG + Sonatype
  credentials. Version is still `2.0.0-SNAPSHOT`.
- **JDK 25.** Blocked on Lombok (see below).

## Known constraints and limitations

- **JDK 21 only.** Lombok doesn't generate members correctly under JDK 25's compiler internals -
  every `@Getter`/`@Setter`/`@Slf4j` becomes "cannot find symbol". Dropping Lombok (entities ->
  records isn't possible for JPA, but hand-written accessors are) would unblock this.
- **`@Repeatable`/`@Target(TYPE)` on `@Audit` are declared but not fully processed.** The aspect
  binds via `@annotation(actLog)`, which matches a single method-level annotation instance.
  Multiple `@Audit` on one method are legal to declare but only the first fires; type-level
  `@Audit` isn't picked up. Documented in `Audits`' javadoc. Implementing this means switching the
  pointcut and iterating `getAnnotationsByType`.
- **JSR-303 constraints are unenforced without a validator on the consumer's classpath.** By
  design (see decision #2), but it means `@Min` violations bind silently in that case.
- **`fail-on-missing-template` scans every bean's methods at startup.** Off by default for that
  reason.
- **`trace_id` is read from MDC key `traceId`.** Correct for Micrometer Tracing's default; a
  consumer using a different key gets nulls.
- **`V2__audit_log_v2.sql` is PostgreSQL dialect** and backfills `outcome='SUCCESS'` for
  pre-existing rows, since 1.x never recorded outcome. Adjust for other databases.

## Git state

- Branch `claude/project-audit-planning-ztbevf`, merged into `main`.
- PR #1 (the earlier Phase 0-6 triage pass) is merged. The v2 work was rebased onto the resulting
  `main` mid-session, so history is linear.
- The plan this work followed is at `/root/.claude/plans/go-through-this-project-starry-bear.md`
  (agent-local, not in the repo) - WP0 through WP7, all complete.
