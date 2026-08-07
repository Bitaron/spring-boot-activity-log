# audit-log-java-client-spring-boot-starter

Opt-in Spring Boot auto-configuration for
[`audit-log-java-client`](../audit-log-java-client)'s `AuditLogHttpClient` - a separate artifact
from the plain client module so a non-Spring-Boot consumer of `AuditLogHttpClient` never gets
`spring-boot-autoconfigure` forced onto its classpath (the client module itself depends on nothing
beyond `spring-web`, deliberately).

## Usage

Add the dependency, then configure:

```properties
audit.log.client.enabled=true
audit.log.client.base-url=https://audit.example.com
audit.log.client.api-key=<tenant-api-key>
```

An `AuditLogHttpClient` bean is then available for injection anywhere in your application:

```java
@Service
class PaymentService {
    private final AuditLogHttpClient auditLogHttpClient;

    PaymentService(AuditLogHttpClient auditLogHttpClient) {
        this.auditLogHttpClient = auditLogHttpClient;
    }
}
```

Off by default (`audit.log.client.enabled=false`) - a bean pointed at a specific external URL and
holding a secret API key must not be registered just because this module happens to be on the
classpath. `audit.log.client.base-url` is required once enabled; there's no sensible default for
which server to talk to.

`audit.log.client.api-key` is whichever secret is configured under
`audit.log.server.api-keys.<tenantId>` on the server side - it determines which tenant this client
acts as. See the server module's own README for the per-tenant authentication model.

## Configuration reference (`audit.log.client.*`)

| Property | Default | Purpose |
|---|---|---|
| `audit.log.client.enabled` | `false` | Master switch |
| `audit.log.client.base-url` | *(required if enabled)* | The REST server module's base URL |
| `audit.log.client.api-key` | - | Which tenant this client acts as |
| `audit.log.client.http.connect-timeout` | `5s` | Connect timeout for every request |
| `audit.log.client.http.read-timeout` | `30s` | Read timeout for every request |

Timeouts are applied via the `RestClient.Builder`-accepting constructor `AuditLogHttpClient`
exposes specifically for this purpose - the plain 2-argument constructor has no way to configure
them, which is why this module doesn't just call that one.

## Overriding the bean

Like every other SPI default in this project's autoconfiguration, the registered
`AuditLogHttpClient` bean is `@ConditionalOnMissingBean` - supply your own `@Bean AuditLogHttpClient`
(e.g. with a custom `ClientHttpRequestFactory` or interceptor via the client module's
`RestClient.Builder`-accepting constructor) to override it entirely.
