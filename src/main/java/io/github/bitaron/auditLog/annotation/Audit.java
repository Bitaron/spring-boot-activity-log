package io.github.bitaron.auditLog.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a method should be audited for system usage tracking purposes.
 * <p>
 * Methods annotated with {@code @Audit} will trigger audit logging mechanisms
 * when invoked. The audit subsystem uses the {@link #type()} value to identify
 * and retrieve corresponding audit data through implementations of the
 * {@link io.github.bitaron.auditLog.contract.AuditLogDataGetter} interface.
 *
 * <p>The audit system matches implementations of {@link io.github.bitaron.auditLog.contract.AuditLogDataGetter#getActivityType()}
 * with the {@code type} specified in this annotation to determine which data getter
 * should be used for collecting audit information.
 *
 * <p><b>Example Usage:</b>
 * <pre>
 * {@code @Audit(type = "USER_LOGIN")}
 * public void loginUser(String username) {
 *     // Method implementation
 * }
 * </pre>
 *
 * This would require an implementation of {@code AuditLogDataGetter} that returns
 * "USER_LOGIN" from its {@code getActivityType()} method.
 *
 * @see io.github.bitaron.auditLog.contract.AuditLogDataGetter
 * @see io.github.bitaron.auditLog.contract.AuditLogDataGetter#getActivityType()
 *
 * @author [Your Name]
 * @version 1.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audit {
    /**
     * Specifies the type of audit.
     * <p>
     * This value should match the audit type returned by the
     * {@link io.github.bitaron.auditLog.contract.AuditLogDataGetter#getActivityType()} method of the corresponding data getter.
     * </p>
     *
     * @return the audit type as a {@code String}
     */
    String type();

}
