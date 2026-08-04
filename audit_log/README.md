# audit-log

A Spring Boot starter that records audit trail entries for annotated methods via AspectJ,
rendering messages from database-stored FreeMarker templates.

## Install

```xml
<dependency>
    <groupId>io.github.bitaron</groupId>
    <artifactId>audit-log</artifactId>
    <version>1.1.0-SNAPSHOT</version>
</dependency>
```

Requires a JPA/Hibernate application (`EntityManager` on the classpath and a configured
`DataSource`). The starter ships its own entities (`AuditLog`, `AuditTemplate`, `AuditGroup`) and
adds them to your application's entity scan automatically without narrowing what your own
application scans - see [`AuditLogEntityScanRegistrar`](src/main/java/io/github/bitaron/auditLog/config/spring/AuditLogEntityScanRegistrar.java)
if you want to know how.

## Usage

```java
@Audit(auditType = "USER_MANAGEMENT", actionName = "update-profile", actionType = "UPDATE",
        templateNameList = {"profile_updated"})
public void updateProfile(UpdateProfileRequest request) { ... }
```

One audit log row is persisted per template name in `templateNameList` that has a matching row in
the `audit_template` table (seed that table yourself - the starter does not ship one). If
`templateNameList` is empty, one row is still recorded with a null message. A method that throws
is recorded the same way via `@AfterThrowing`; a failure to record an entry never affects the
audited method's own outcome (see "Failure isolation" below).

Exclude a parameter from the recorded arguments with `@AuditIgnore`:

```java
@Audit(auditType = "AUTH", actionName = "login", templateNameList = {"login_attempt"})
public LoginResult login(LoginRequest request, @AuditIgnore HttpServletResponse response) { ... }
```

## Configuration (`audit.log.*`)

| Property | Default | Description |
|---|---|---|
| `audit.log.enabled` | `true` | Master switch; disables the aspect, executor, and entity scan entirely. |
| `audit.log.header-mappings.requesterId` | `X-USER-ID` | Header read for the actor id when no `AuditLogGenericDataGetter` bean is configured. |
| `audit.log.header-mappings.requesterName` | `X-USER-NAME` | Same, for the actor name. |
| `audit.log.trust-forwarded-headers` | `false` | Whether to trust `X-Forwarded-For`/`Proxy-Client-IP`/`WL-Proxy-Client-IP` for the client IP. |
| `audit.log.masked-fields` | `password, secret, token, authorization, creditCardNumber` | Field names redacted (at any depth) in the persisted `data` JSON. |
| `audit.log.max-serialized-data-length` | `8192` | Characters after which the serialized `data` payload is truncated into a `{truncated, preview}` envelope. |
| `audit.log.executor.core-pool-size` / `max-pool-size` / `queue-capacity` | `2` / `10` / `500` | Sizing for the dedicated executor audit writes are dispatched to. |

## Extension points

- **`AuditLogGenericDataGetter`** - supply your own actor/client resolution (e.g. from
  `SecurityContextHolder`, a JWT claim, a non-HTTP context). If Spring Security is on the
  classpath and you don't provide one, a `SecurityContextHolder`-backed default is registered
  automatically - see "Trust model" below.
- **`AuditLogTemplateResolver`** - swap out FreeMarker for your own template engine.
- **`AuditLogArgumentSerializer`** - swap out the default Jackson-based serializer.
- **`AuditLogLocationResolver`** - plug in IP geolocation (e.g. MaxMind GeoIP2); none is bundled.

## Trust model

With no `AuditLogGenericDataGetter` configured, the actor identity is read from client-supplied
HTTP headers (`X-USER-ID`/`X-USER-NAME` by default) - **these are spoofable by the caller** unless
a trusted reverse proxy strips/overwrites them before the request reaches your application. The
client IP is similarly only read from `X-Forwarded-For`-style headers when
`audit.log.trust-forwarded-headers=true` is explicitly set, since those headers are equally
spoofable without a trusted proxy in front. For a verified actor identity, supply an
`AuditLogGenericDataGetter` backed by your real authentication mechanism, or rely on the
Spring-Security-backed default described above.

FreeMarker templates are read from the `audit_template` table and executed. The resolver
disables the `?api` built-in and uses `SAFER_RESOLVER` to block reflective escapes into arbitrary
classes, but write access to `audit_template` is still effectively the ability to execute
template logic in this process - restrict who can write to that table the same way you would
restrict deploy access.

## Failure isolation

A failure anywhere in the audit pipeline - a malformed template, an unserializable argument, a
database error - is caught and logged at `WARN`. It is designed to never surface to the audited
method's caller. Writes are also dispatched to a dedicated executor (not `@Async`, so no
`@EnableAsync` is imposed on your application) and persisted in their own transaction
(`REQUIRES_NEW`), so they run off the request thread and don't roll back with a failed business
transaction.

## Schema

See the entity classes in `io.github.bitaron.auditLog.entity` for the mapped columns. Use
`ddl-auto` for local development only; for production, manage `audit_log`/`audit_template`/
`audit_group` with your own migration tool.
