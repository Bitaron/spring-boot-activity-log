# Generating a client for the audit-log REST server

The audit-log REST server (`audit-log-spring-boot-server`) speaks Protobuf on the wire - see
`audit_log/audit-log-server-proto/src/main/proto/auditlog/v1/audit_event.proto`, the single source
of truth for the wire format. That file is plain, language-agnostic Protocol Buffers IDL, so any
language `protoc` supports can generate a typed client directly from it - you are not limited to
Java, and you don't need this repository's build to do it.

## Java: use the pre-built module

If you're writing Java, you don't need to run `protoc` yourself at all - depend on
`audit-log-java-client` (a thin `RestClient` wrapper) or `audit-log-server-proto` directly (just
the generated message types, no HTTP client) and you're done:

```xml
<dependency>
    <groupId>io.github.bitaron</groupId>
    <artifactId>audit-log-java-client</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

```java
AuditLogHttpClient client = new AuditLogHttpClient("https://audit.example.com", apiKey);
client.ingest(AuditEventRequest.newBuilder().setAuditType("payment.captured").build());
```

The rest of this document is for every other language.

## Any other language: generate directly from the `.proto` file

1. Get the schema file. Either clone this repository and use it in place:

   ```
   audit_log/audit-log-server-proto/src/main/proto/auditlog/v1/audit_event.proto
   ```

   or, if you only have the published `audit-log-server-proto` jar, extract it from there - the
   `.proto` source is bundled inside the jar itself under `proto/`, not just the compiled Java
   stubs:

   ```bash
   unzip -p audit-log-server-proto-2.0.0-SNAPSHOT.jar proto/auditlog/v1/audit_event.proto \
       > audit_event.proto
   ```

2. Install `protoc` (the Protobuf compiler) for your platform - see
   [protobuf.dev/downloads](https://protobuf.dev/downloads/) - matching or newer than the version
   this repository pins (`protobuf.version` in `audit_log/pom.xml`, currently `3.25.5`).

3. Generate. Examples for a few common languages/plugins - the exact plugin name and flags depend
   on which `protoc` plugin you're using for your language:

   ```bash
   # Python
   protoc --python_out=. --pyi_out=. auditlog/v1/audit_event.proto

   # Go (requires protoc-gen-go on PATH: go install google.golang.org/protobuf/cmd/protoc-gen-go)
   protoc --go_out=. --go_opt=paths=source_relative auditlog/v1/audit_event.proto

   # TypeScript (using ts-proto: npm install -g ts-proto)
   protoc --plugin=protoc-gen-ts_proto=$(npm root -g)/ts-proto/protoc-gen-ts_proto \
       --ts_proto_out=. auditlog/v1/audit_event.proto

   # C#
   protoc --csharp_out=. auditlog/v1/audit_event.proto
   ```

   Run these from the directory *containing* `auditlog/`, so the `package auditlog.v1;` and
   `option java_package = ...` declarations resolve the same relative layout the file itself uses.

4. You now have typed request/response classes for `AuditEventRequest`, `AuditEventResponse`,
   `AuditRecordProto`, `AuditQueryRequest`, and `AuditQueryResponse` in your language. Wire them up
   to `POST /audit-log/events` and `GET /audit-log/records` with whichever HTTP client your
   language uses, sending/accepting `Content-Type: application/x-protobuf` (binary, recommended)
   or `application/json` (Protobuf's canonical JSON mapping - camelCase field names, `int64`
   fields as JSON strings - convenient for debugging with `curl`, less efficient than binary), plus
   the `X-API-Key` header for whichever tenant you're calling as
   (`audit.log.server.api-keys.<tenantId>`) - which tenant a request acts as is determined by
   which key it presents, not by anything the client sends elsewhere.

## Why this works reliably: schema compatibility rules

Self-service codegen only stays useful if the schema doesn't break existing generated code out
from under you. `audit_event.proto` documents (and this project follows) these rules for every
future change:

1. **Never reuse or renumber a field number.** A removed field's number goes into a `reserved`
   statement instead of being available for the next field added.
2. **Only add fields as the evolution mechanism.** Never change an existing field's type - `protoc`
   will still happily compile that, but it breaks every client compiled against the old schema.
3. **Every enum's zero value is an explicit `..._UNSPECIFIED` sentinel** (proto3 gives every field
   a default value on the wire; `0` must never silently mean a real, meaningful value).
4. New messages and new fields on existing messages are always safe to add - old and new clients
   stay interoperable in both directions without a coordinated deploy.

Publishing per-language packages to each language's own registry (PyPI, npm, NuGet, ...) is a
packaging/release decision this project doesn't make for you today - the steps above are meant to
be fast enough that you don't need one.
