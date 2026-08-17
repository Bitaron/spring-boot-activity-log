# spring-boot-activity-log

Aggregator repo for [`audit-log`](audit_log/audit-log-spring-boot-autoconfigure/README.md), a
Spring Boot starter that records audit trail entries for `@Audit`-annotated methods, and
[`audit_log_usage_example`](audit_log_usage_example), a runnable demo app that consumes it. See
[`MIGRATION.md`](MIGRATION.md) if you're upgrading from `1.x`, or
[`AGENTS.md`](AGENTS.md) if you're a coding agent about to make a change here.

## Documentation

| | |
|---|---|
| **Docs site** | https://bitaron.github.io/spring-boot-activity-log/ - a hub linking everything below in one place (aggregated Javadoc, the REST server's Swagger UI, and every Markdown doc, rendered). Built by [`.github/workflows/pages.yml`](.github/workflows/pages.yml) on every push to `main`; see that workflow's header comment for the one-time repository setting it needs. |
| **Library API** | Each module's own README (linked under "Modules" below) plus generated Javadoc - `mvn -f audit_log/pom.xml org.apache.maven.plugins:maven-javadoc-plugin:3.8.0:aggregate` builds it locally at `audit_log/target/site/apidocs/index.html`. |
| **REST server API** | [Swagger UI](audit_log/audit-log-spring-boot-server/README.md#api-docs-swagger-ui) - bundled into `audit-log-spring-boot-server` itself, served at `/swagger-ui/index.html` on any running instance (see the docs site for a static copy). Spec source: [`audit-log-server-openapi.yaml`](audit_log/audit-log-spring-boot-server/src/main/resources/static/openapi/audit-log-server-openapi.yaml). |
| **gRPC server API** | [`audit_event.proto`](audit_log/audit-log-server-proto/src/main/proto/auditlog/v1/audit_event.proto) (the `AuditLogService` block) plus [`audit-log-spring-boot-grpc-server/README.md`](audit_log/audit-log-spring-boot-grpc-server/README.md). |
| **Configuration** | [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) - every `audit.log.*` property, in one place. |
| **Migrating / what's new** | [`MIGRATION.md`](MIGRATION.md) |
| **Design decisions & history** | [`AGENTS.md`](AGENTS.md) (dense, agent-facing spec) and [`HANDOFF.md`](HANDOFF.md) (narrative rationale) |
| **Scaling** | [`docs/SCALING.md`](docs/SCALING.md) |
| **Client codegen (any language)** | [`docs/CLIENT_CODEGEN.md`](docs/CLIENT_CODEGEN.md) |

## Quick start

```xml
<dependency>
    <groupId>io.github.bitaron</groupId>
    <artifactId>audit-log-spring-boot-starter</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

```java
@Audit(auditType = "USER_MANAGEMENT", actionName = "update-profile", actionType = "UPDATE",
        templates = {"profile_updated"})
public void updateProfile(UpdateProfileRequest request) { ... }
```

That's it - every invocation of an `@Audit`-annotated method now produces one `audit_log` row
(`outcome`, `duration_ms`, actor/client fields, and any rendered `templates`), dispatched
asynchronously and deferred until the caller's transaction commits by default. See the
[full usage guide](audit_log/audit-log-spring-boot-autoconfigure/README.md) for actor resolution,
delivery-mode control, reading records back, retention, and the optional REST/gRPC servers for
non-JVM callers.

## Build

Requires JDK 21. (Not 25: Lombok doesn't yet generate members correctly under JDK 25's compiler
internals - see the comment in `pom.xml` for details. Revisit once Lombok catches up.)

```bash
mvn clean install
```

This builds the starter, installs it to the local Maven repository, then builds the demo app and
the standalone server app (below) against that local build (not a published release).

To build **just the library jars** - installable to a local repo, or publishable, for use as a
dependency in any Spring Boot app - without the demo/standalone apps:

```bash
mvn -f audit_log/pom.xml clean install
```

## Run the demo

Requires the [Build](#build) step above - `mvn clean install` from the repo root - to have run
first - the demo depends on `audit-log-spring-boot-starter:2.0.0-SNAPSHOT`, which is only in your
*local* Maven repository, not published to Central. Skipping straight to the commands below on a
fresh clone fails with
`Could not find artifact io.github.bitaron:audit-log-spring-boot-starter:jar:2.0.0-SNAPSHOT`. Note
that `mvn -f audit_log/pom.xml clean install` alone is *not* enough here even though it builds
every library module: it never installs the root `spring-boot-activity-log-parent` pom that those
modules' installed POMs still declare as their Maven parent, so resolving them back out of the
local repo from a separate build (like this one) fails one level further up the parent chain
instead. Run the plain root `mvn clean install` to get both.

```bash
cd audit_log_usage_example
mvn spring-boot:run
```

Starts on `http://localhost:8080` with an in-memory H2 database (seeded via `data.sql`, schema via
`ddl-auto=create-drop` - see `application.properties`). Try:

```bash
curl http://localhost:8080/test        # 200, records one audit_log row with outcome=SUCCESS
curl http://localhost:8080/test/fail   # 500, records one audit_log row with outcome=FAILURE
```

Inspect the database live at `http://localhost:8080/h2-console` (JDBC URL
`jdbc:h2:mem:activitylog`, user `sa`, empty password).

For a realistic run against PostgreSQL instead: `docker compose up -d` in
`audit_log_usage_example`, then `mvn spring-boot:run -Dspring-boot.run.profiles=postgres`.

## Run the REST server standalone

`audit_log/audit-log-spring-boot-server` is a pure library (no main class) - `audit_log_standalone_server`
is the runnable app around it, for a caller with no in-process JVM to depend on the library from.
Same prerequisite as the demo above: run the plain `mvn clean install` from the repo root first, so
the `audit-log-spring-boot-server:2.0.0-SNAPSHOT` this app depends on (and its own parent POMs) are
in your local Maven repository.

```bash
AUDIT_LOG_SERVER_APIKEYS_DEFAULT=<your-secret> mvn -f audit_log_standalone_server/pom.xml spring-boot:run
```

`audit.log.server.api-keys.<tenantId>` has no default - the app fails fast at startup if none is
configured, since there's no safe default that leaves the ingest/query endpoints open. Each key
authenticates exactly one tenant; `default` is this app's out-of-the-box tenant id, chosen because
it has no dashes/dots (Spring's relaxed environment-variable binding can't always map a `Map` key
that does). See [its README](audit_log_standalone_server/README.md) for the Postgres profile, a
curl smoke test, and running more than one tenant.

## Modules

- **`audit_log/audit-log-spring-boot-autoconfigure`** - the auto-configuration and implementation.
  See [its README](audit_log/audit-log-spring-boot-autoconfigure/README.md) for configuration,
  extension points, and the actor-identity trust model.
- **`audit_log/audit-log-spring-boot-starter`** - the pom-only dependency aggregator consuming
  applications should actually depend on (pulls in the autoconfigure module plus
  `spring-boot-starter-aspectj`).
- **`audit_log/audit-log-server-proto`** - the Protobuf (`.proto`) wire schema and generated Java
  stubs (Protobuf messages plus, as of WP18, a gRPC service) for the REST and gRPC server modules
  below, with no Spring dependency of its own. See [`docs/CLIENT_CODEGEN.md`](docs/CLIENT_CODEGEN.md)
  to generate a client in any language directly from the schema.
- **`audit_log/audit-log-spring-boot-server`** - an optional REST ingestion/query server
  (`POST /audit-log/events`, `GET /audit-log/records`) for callers with no `@Audit`-annotated
  method invocation to intercept. Off by default - see `audit.log.server.*` properties in
  [`MIGRATION.md`](MIGRATION.md).
- **`audit_log/audit-log-spring-boot-grpc-server`** - the gRPC equivalent (WP18), same three
  operations on the same wire messages, for callers that prefer gRPC over REST. Off by default -
  see [its README](audit_log/audit-log-spring-boot-grpc-server/README.md) and `audit.log.grpc.*`
  properties in [`MIGRATION.md`](MIGRATION.md). Cannot be enabled in the same application as
  `audit-log-spring-boot-server`.
- **`audit_log/audit-log-java-client`** - a typed Java HTTP client for the REST server module
  above, and **`audit_log/audit-log-java-client-spring-boot-starter`** - its optional Spring Boot
  auto-config, registering an `AuditLogHttpClient` bean from `audit.log.client.*` properties.
- **`audit_log_usage_example`** - a minimal Spring Boot app wiring the starter in, used both as a
  runnable demo and as an integration test target (`mvn test` in that module).
- **`audit_log_standalone_server`** - the runnable standalone deployment of
  `audit-log-spring-boot-server`, for a caller with no in-process JVM to depend on the library
  from. H2 by default, `postgres` profile available; see [its README](audit_log_standalone_server/README.md).

For large-data operation (pagination at scale, retention, table partitioning) see
[`docs/SCALING.md`](docs/SCALING.md).
