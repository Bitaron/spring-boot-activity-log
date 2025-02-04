package io.github.bitaron.auditLog.contract;

import io.github.bitaron.auditLog.dto.AuditLogClientData;
import io.github.bitaron.auditLog.dto.AuditLogTemplateData;

import java.util.List;

/**
 * Provides audit log data collection capabilities for methods annotated with {@link io.github.bitaron.auditLog.annotation.Audit}.
 * <p>
 * Implementations of this interface supply contextual information for audit logging based on
 * the {@link io.github.bitaron.auditLog.annotation.Audit#type()} specified in the corresponding annotated method. The audit subsystem
 * uses the value returned by {@link #getActivityType()} to match with {@code @Audit} annotations.
 *
 * <p><b>Implementation Requirements:</b>
 * <ul>
 *   <li>{@link #getActivityType()} must exactly match an {@code @Audit(type = "value")} annotation</li>
 *   <li>All methods should return non-null values unless explicitly documented otherwise</li>
 *   <li>Implementations are typically registered with the audit subsystem at runtime</li>
 * </ul>
 *
 * <p><b>Example Implementation:</b>
 * <pre>
 * public class LoginAuditDataGetter implements AuditLogDataGetter {
 *    public String getActivityType() { return "USER_LOGIN"; }
 *    public String getActionName() { return "User Authentication"; }
 *    // ... other method implementations ...
 * }
 * </pre>
 *
 * @see io.github.bitaron.auditLog.annotation.Audit
 * @see AuditLogTemplateData
 * @see AuditLogClientData
 */
public interface AuditLogDataGetter {
    /**
     * Returns the activity type identifier that matches the {@link io.github.bitaron.auditLog.annotation.Audit#type()} value.
     * This value is used by the audit system to associate audit configurations with
     * specific method invocations.
     *
     * @return A non-null string identifier matching an {@code @Audit} annotation type
     */
    String getActivityType();

    /**
     * Provides a name for the audited action.
     *
     * @return A descriptive name for the audit action (e.g., "Account Update")
     */
    String getActionName();

    /**
     * Specifies the category classification for the audit action.
     *
     * @return The action category (e.g., "Security", "Data Modification")
     */
    String getActionType();

    /**
     * Identifies the functional group or subsystem associated with the action.
     *
     * @return The group name responsible for the audited operation
     */
    String getGroupName();

    /**
     * Lists audit log templates to be populated with contextual data. This is expected to be ordered
     *
     * @return A list of template identifiers that will receive audit data.
     *         May return an empty list if no templates are required.
     */
    List<String> getTemplateList();

    /**
     * Generates audit-specific data using both static configuration and runtime context.
     *
     * @param auditLogClientData Runtime context information including method parameters, method response
     *        and invocation metadata
     * @return Populated template data structure containing audit details
     * @see AuditLogClientData
     */
    AuditLogTemplateData getData(AuditLogClientData auditLogClientData);
}
