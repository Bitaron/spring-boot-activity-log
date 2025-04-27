package io.github.bitaron.auditLog.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "audit.log")
public class AuditLogProperties {
    public static final String REQUESTER_ID = "requesterId";
    public static final String REQUESTER_NAME = "requesterName";
    /**
     * Map of audit actor identifiers to their corresponding HTTP header names.
     * Example: userId -> X-User-Id
     */
    private Map<String, String> headerMappings = new HashMap<>();

    // Getters & Setters
    public Map<String, String> getHeaderMappings() {
        return headerMappings;
    }

    public void setHeaderMappings(Map<String, String> headerMappings) {
        this.headerMappings = headerMappings;
    }


    public String getHeaderFor(String type) {
        String value = headerMappings.getOrDefault(type, "");
        if (value.isEmpty()) {
            if (type.equals(REQUESTER_NAME)) {
                return "X-USER-NAME";
            }
            if (type.equals(REQUESTER_ID)) {
                return "X-USER-ID";
            }
        }
        return value;
    }
}
