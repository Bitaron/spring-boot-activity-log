# audit-log-standalone-server

The reference standalone deployment of `audit-log-spring-boot-server`, for a caller with no
in-process JVM to depend on the library from. `audit-log-spring-boot-server` is deliberately a
pure library (no main class, no `spring-boot-maven-plugin`) - this module is the thin runnable app
around it, the same relationship `audit_log_usage_example` has to the core `audit-log` starter.

Not part of the published library (`audit_log/pom.xml`'s modules) - see the root
[`AGENTS.md`](../AGENTS.md) for the two build paths this repo supports.

## Run it

Requires at least one `audit.log.server.api-keys.<tenantId>` entry to be supplied externally -
there is no default, since an unconfigured module would otherwise leave the ingest/query endpoints
open (see `AuditLogServerAutoConfiguration`). Each key authenticates exactly one tenant (WP16) - a
caller presenting it can only ever act as that tenant, never another one, regardless of what it
puts in a request body or header. Spring Boot's relaxed binding for environment variables reliably
maps a `Map<String,String>` property key only when the key itself has no dashes/dots, which is why
this module's default tenant id is `default`:

```bash
# H2 in-memory, zero setup
AUDIT_LOG_SERVER_APIKEYS_DEFAULT=<your-secret> mvn spring-boot:run

# ...or pass it directly as a run argument (works for any tenant id, dashes/dots included):
mvn spring-boot:run -Dspring-boot.run.arguments=--audit.log.server.api-keys.default=<your-secret>
```

For a persistent, realistic run against PostgreSQL instead of in-memory H2:

```bash
docker compose up -d
AUDIT_LOG_SERVER_APIKEYS_DEFAULT=<your-secret> mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

Build once, run the jar (closer to an actual deployment than the `spring-boot:run` dev loop):

```bash
mvn clean package
AUDIT_LOG_SERVER_APIKEYS_DEFAULT=<your-secret> java -jar target/audit-log-standalone-server-*.jar
```

To run more than one tenant against this same deployment, add more keys the same way:
`AUDIT_LOG_SERVER_APIKEYS_DEFAULT=<secret-1>` plus, e.g.,
`-Dspring-boot.run.arguments=--audit.log.server.api-keys.acme-corp=<secret-2>`.

## Verify it's up

```bash
curl -X POST http://localhost:8080/audit-log/events \
  -H "X-API-Key: <your-secret>" -H "Content-Type: application/json" \
  -d '{"auditType":"smoke-test"}'
# -> 202 {"accepted": true}, tagged with whichever tenant <your-secret> authenticates

curl "http://localhost:8080/audit-log/records?auditType=smoke-test" -H "X-API-Key: <your-secret>"
# -> only that same tenant's rows, even if other tenants' keys have ingested matching auditTypes
```

## Production notes

- Per-tenant API keys are a first cut, not a complete auth solution - see the Javadoc on
  `AuditLogServerProperties.apiKeys`. Front this with real authn/authz (mTLS, an OAuth2 resource
  server, network policy) before exposing it beyond a trusted network.
- `application.properties` defaults to `spring.jpa.hibernate.ddl-auto=create-drop`, a dev
  convenience. For a real deployment, manage the schema with
  [`db/migration/V2__audit_log_v2.sql`](../db/migration/V2__audit_log_v2.sql) instead - see
  `AuditSchemaValidator` for the startup check this relies on.
