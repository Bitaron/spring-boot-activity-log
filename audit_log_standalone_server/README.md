# audit-log-standalone-server

The reference standalone deployment of `audit-log-spring-boot-server`, for a caller with no
in-process JVM to depend on the library from. `audit-log-spring-boot-server` is deliberately a
pure library (no main class, no `spring-boot-maven-plugin`) - this module is the thin runnable app
around it, the same relationship `audit_log_usage_example` has to the core `audit-log` starter.

Not part of the published library (`audit_log/pom.xml`'s modules) - see the root
[`AGENTS.md`](../AGENTS.md) for the two build paths this repo supports.

## Run it

Requires `audit.log.server.api-key` to be supplied externally - there is no default, since an
unset key would otherwise leave the ingest/query endpoints open (see
`AuditLogServerAutoConfiguration`). Spring Boot's relaxed binding for environment variables drops
dashes entirely, so `audit.log.server.api-key` becomes the env var `AUDIT_LOG_SERVER_APIKEY`, not
`AUDIT_LOG_SERVER_API_KEY`:

```bash
# H2 in-memory, zero setup
AUDIT_LOG_SERVER_APIKEY=<your-secret> mvn spring-boot:run

# ...or pass it directly as a run argument instead of relying on that env var mapping:
mvn spring-boot:run -Dspring-boot.run.arguments=--audit.log.server.api-key=<your-secret>
```

For a persistent, realistic run against PostgreSQL instead of in-memory H2:

```bash
docker compose up -d
AUDIT_LOG_SERVER_APIKEY=<your-secret> mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

Build once, run the jar (closer to an actual deployment than the `spring-boot:run` dev loop):

```bash
mvn clean package
AUDIT_LOG_SERVER_APIKEY=<your-secret> java -jar target/audit-log-standalone-server-*.jar
```

## Verify it's up

```bash
curl -X POST http://localhost:8080/audit-log/events \
  -H "X-API-Key: <your-secret>" -H "Content-Type: application/json" \
  -d '{"auditType":"smoke-test"}'
# -> 202 {"accepted": true}

curl "http://localhost:8080/audit-log/records?auditType=smoke-test" -H "X-API-Key: <your-secret>"
```

## Production notes

- The API key is a first cut, not a complete auth solution - see the Javadoc on
  `AuditLogServerProperties.apiKey`. Front this with real authn/authz (mTLS, an OAuth2 resource
  server, network policy) before exposing it beyond a trusted network.
- `application.properties` defaults to `spring.jpa.hibernate.ddl-auto=create-drop`, a dev
  convenience. For a real deployment, manage the schema with
  [`db/migration/V2__audit_log_v2.sql`](../db/migration/V2__audit_log_v2.sql) instead - see
  `AuditSchemaValidator` for the startup check this relies on.
