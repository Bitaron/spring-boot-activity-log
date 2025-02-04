package io.github.bitaron.auditLog.dto;

import lombok.Data;

/**
 * Represents client-specific execution context data for audit logging purposes.
 * <p>
 * This class encapsulates runtime information about an audited method invocation, including:
 * <ul>
 *   <li>Method arguments passed to the audited method</li>
 *   <li>Return value or exception produced by the method</li>
 *   <li>Actor/initiator identifier for the operation</li>
 *   <li>Execution status (success/exception)</li>
 * </ul>
 *
 * <p>Instances are typically created by the audit framework during method interception and
 * passed to {@link io.github.bitaron.auditLog.contract.AuditLogDataGetter#getData(AuditLogClientData)} for audit log generation.
 *
 * <p><b>Thread Safety:</b> This class is not thread-safe and should only be used within
 * the context of a single method invocation.
 *
 * @see io.github.bitaron.auditLog.annotation.Audit
 * @see io.github.bitaron.auditLog.contract.AuditLogDataGetter
 */
@Data
public class AuditLogClientData {
    /**
     * The identifier of the actor (user or system) initiating the action.
     * May be {@code null} if not applicable or unavailable.
     */
    private String actorId;

    /**
     * The arguments passed to the audited method. Preserved as:
     * <ul>
     *   <li>Single argument: the argument object directly</li>
     *   <li>Multiple arguments: object array containing all parameters</li>
     *   <li>Zero arguments: {@code null} or empty array</li>
     * </ul>
     */
    private Object args;

    /**
     * The successful method return value. Will be {@code null} when:
     * <ul>
     *   <li>The method has a {@code void} return type</li>
     *   <li>An exception occurred during execution</li>
     * </ul>
     */
    private Object response;

    /**
     * Flag indicating whether an exception occurred during method execution.
     * When {@code true}, the {@link #exception} field contains the throwable.
     */
    private boolean exceptionThrown;

    /**
     * The exception thrown during method execution, if any.
     * Always {@code null} when {@link #exceptionThrown} is {@code false}.
     */
    private Object exception;

    /**
     * Constructs an audit log context container with execution results.
     *
     * @param args The method parameters received by the audited method.
     *        May be {@code null}, a single object, or an object array.
     * @param response The method return value when no exception occurred,
     *        or the exception object when {@code exceptionThrown} is {@code true}.
     *        May be {@code null} for void methods or unthrown exceptions.
     * @param exceptionThrown {@code true} if the method terminated with an exception,
     *        {@code false} for successful execution
     */
    public AuditLogClientData(Object args, Object response, boolean exceptionThrown) {
        this.args = args;
        this.exceptionThrown = exceptionThrown;
        if (!exceptionThrown) {
            this.response = response;
        } else {
            this.exception = response;
        }
    }
}
