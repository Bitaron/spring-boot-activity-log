# spring-boot-activity-log

Aggregator repo for [`audit-log`](audit_log/audit-log-spring-boot-autoconfigure/README.md), a
Spring Boot starter that records audit trail entries for `@Audit`-annotated methods, and
[`audit_log_usage_example`](audit_log_usage_example), a runnable demo app that consumes it. See
[`MIGRATION.md`](MIGRATION.md) if you're upgrading from `1.x`.

## Build

Requires JDK 21. (Not 25: Lombok doesn't yet generate members correctly under JDK 25's compiler
internals - see the comment in `pom.xml` for details. Revisit once Lombok catches up.)

```bash
mvn clean install
```

This builds the starter, installs it to the local Maven repository, then builds the demo app
against that local build (not a published release).

## Run the demo

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

## Modules

- **`audit_log/audit-log-spring-boot-autoconfigure`** - the auto-configuration and implementation.
  See [its README](audit_log/audit-log-spring-boot-autoconfigure/README.md) for configuration,
  extension points, and the actor-identity trust model.
- **`audit_log/audit-log-spring-boot-starter`** - the pom-only dependency aggregator consuming
  applications should actually depend on (pulls in the autoconfigure module plus
  `spring-boot-starter-aop`).
- **`audit_log_usage_example`** - a minimal Spring Boot app wiring the starter in, used both as a
  runnable demo and as an integration test target (`mvn test` in that module).
