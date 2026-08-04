package io.github.bitaron.auditLog.core;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.bitaron.auditLog.contract.AuditLogArgumentSerializer;
import io.github.bitaron.auditLog.properties.AuditLogProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.security.Principal;
import java.util.Set;

/**
 * Default {@link AuditLogArgumentSerializer}, backed by Jackson.
 * <p>
 * Feeding an audited method's raw arguments to a general-purpose serializer is unsafe: servlet
 * request/response objects reflect over container internals and throw on modern JDKs, cyclic
 * object graphs overflow the stack, and every field - passwords included - would otherwise end
 * up verbatim in the audit table. This implementation registers placeholder serializers for
 * well-known request-scoped types, wherever they appear in the object graph (not just at the top
 * level - Jackson matches these by interface against the runtime type of every nested value),
 * tolerates getter-only/empty beans instead of throwing, redacts field names configured via
 * {@code AuditLogProperties.maskedFields}, and serializes {@link Throwable} as a compact
 * {@code {type, message}} object rather than Jackson's default reflection-based dump (which
 * walks the full stack trace including class loader/module internals for every frame - noisy,
 * and large enough on its own to blow past {@code maxSerializedDataLength} on every recorded
 * failure). If serialization still fails for an unanticipated reason, a placeholder string is
 * returned rather than propagating - a failure to log an argument must never fail the audited
 * call itself.
 */
@Slf4j
public class JacksonAuditLogArgumentSerializer implements AuditLogArgumentSerializer {

    private final ObjectMapper objectMapper;
    private final AuditLogProperties properties;

    public JacksonAuditLogArgumentSerializer(AuditLogProperties properties) {
        this.properties = properties;
        SimpleModule module = new SimpleModule("audit-log-safe-serialization");
        registerPlaceholder(module, "jakarta.servlet.http.HttpServletRequest");
        registerPlaceholder(module, "jakarta.servlet.http.HttpServletResponse");
        registerPlaceholder(module, "jakarta.servlet.http.HttpSession");
        registerPlaceholder(module, "org.springframework.web.multipart.MultipartFile");
        module.addSerializer(Principal.class, ToStringSerializer.instance);
        module.addSerializer(Throwable.class, new ThrowableSerializer());
        this.objectMapper = JsonMapper.builder()
                .addModule(module)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .configure(SerializationFeature.FAIL_ON_SELF_REFERENCES, false)
                .build();
    }

    @SuppressWarnings("unchecked")
    private void registerPlaceholder(SimpleModule module, String className) {
        try {
            Class<Object> type = (Class<Object>) Class.forName(className);
            module.addSerializer(type, new PlaceholderSerializer(type));
        } catch (ClassNotFoundException e) {
            // Type isn't on the classpath at all (e.g. no servlet API present) - nothing to guard against.
        }
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            mask(tree, properties.getMaskedFields());
            return truncate(objectMapper.writeValueAsString(tree));
        } catch (Exception e) {
            log.warn("Failed to serialize audit log data for type {}; recording a placeholder instead",
                    value.getClass().getName(), e);
            return "\"" + value.getClass().getSimpleName() + " (unserializable)\"";
        }
    }

    private void mask(JsonNode node, Set<String> maskedFields) {
        if (node == null || maskedFields.isEmpty()) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            objectNode.fieldNames().forEachRemaining(name -> {
                if (maskedFields.contains(name)) {
                    objectNode.put(name, "***");
                } else {
                    mask(objectNode.get(name), maskedFields);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> mask(child, maskedFields));
        }
    }

    /**
     * Truncating a JSON string by cutting characters and appending a suffix produces invalid
     * JSON - harmless for a plain text column, but {@code audit_log.data} is typically mapped
     * {@code @JdbcTypeCode(SqlTypes.JSON)}, and a JSON-typed column rejects malformed JSON on
     * write. Truncation therefore wraps the cut content in a small, always-valid JSON envelope
     * instead of concatenating a suffix onto raw JSON text.
     */
    private String truncate(String json) {
        int max = properties.getMaxSerializedDataLength();
        if (max <= 0 || json.length() <= max) {
            return json;
        }
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("truncated", true);
            envelope.put("originalLength", json.length());
            envelope.put("preview", json.substring(0, max));
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return "\"(truncated, " + json.length() + " chars)\"";
        }
    }

    private static final class PlaceholderSerializer extends JsonSerializer<Object> {
        private final Class<?> type;

        private PlaceholderSerializer(Class<?> type) {
            this.type = type;
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(type.getSimpleName() + " (not serialized)");
        }
    }

    private static final class ThrowableSerializer extends JsonSerializer<Throwable> {
        @Override
        public void serialize(Throwable value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("type", value.getClass().getName());
            gen.writeStringField("message", value.getMessage());
            gen.writeEndObject();
        }
    }
}
