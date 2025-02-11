package io.github.bitaron.auditLog.core;


import io.github.bitaron.auditLog.annotation.Audit;
import io.github.bitaron.auditLog.contract.AuditLogDataGetter;
import io.github.bitaron.auditLog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditLog.contract.TemplateResolver;
import io.github.bitaron.auditLog.dto.AuditLogClientData;
import io.github.bitaron.auditLog.repository.AuditGroupRepository;
import io.github.bitaron.auditLog.repository.AuditLogRepository;
import io.github.bitaron.auditLog.repository.AuditTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.List;


/**
 * Aspect-oriented programming component that handles audit logging for methods annotated with {@link Audit}.
 * <p>
 * This aspect intercepts method executions marked with {@code @Audit} annotations and coordinates the audit logging
 * process through the following flow:
 * <ol>
 *   <li>Captures method arguments and execution outcome (success or exception)</li>
 *   <li>Constructs audit context data using {@link AuditLogClientData}</li>
 *   <li>Delegates logging operations to {@link AuditLogger}</li>
 * </ol>
 *
 * <p><b>Advice Methods:</b>
 * <ul>
 *   <li>{@link #logMethodActionSuccess} - Handles successful method executions</li>
 *   <li>{@link #logMethodActionException} - Handles method executions that throw exceptions</li>
 * </ul>
 *
 * <p><b>Dependencies:</b>
 * <ul>
 *   <li>{@link AuditLogGenericDataGetter} - Provides environmental/contextual audit data</li>
 *   <li>{@link AuditLogDataGetter} implementations - Supply activity-specific audit data</li>
 *   <li>{@link AuditLogRepository} - Handles audit record persistence</li>
 *   <li>{@link TemplateResolver} - Formats audit data for storage</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This aspect is typically configured as a Spring singleton bean. All dependencies should be
 * thread-safe when used in concurrent environments.
 *
 * @see Audit
 * @see AuditLogger
 * @see org.aspectj.lang.annotation.Aspect
 */
@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    private AuditLogger auditLogger;

    /**
     * Constructs the audit aspect with required dependencies.
     *
     * @param auditLogGenericDataGetter Provides generic audit context information
     * @param auditLogDataGetterList List of activity-specific data getters
     * @param auditLogRepository Repository for persisting audit records
     * @param templateResolver Template engine for formatting log entries
     */
    public AuditLogAspect(AuditLogGenericDataGetter auditLogGenericDataGetter,
                          List<AuditLogDataGetter> auditLogDataGetterList,
                          AuditLogRepository auditLogRepository,
                          AuditTemplateRepository auditTemplateRepository,
                          AuditGroupRepository auditGroupRepository,
                          TemplateResolver templateResolver) {
        this.auditLogger = new AuditLogger(auditLogGenericDataGetter,
                auditLogDataGetterList, auditLogRepository,auditTemplateRepository,auditGroupRepository, templateResolver);
    }

    /**
     * Logs successful method executions after normal return.
     *
     * @param joinPoint AspectJ join point providing access to method signature and arguments
     * @param actLog The {@link Audit} annotation from the intercepted method
     * @param response The method's return value
     */
    @AfterReturning(pointcut = "@annotation(actLog)", returning = "response")
    public void logMethodActionSuccess(JoinPoint joinPoint, Audit actLog, Object response) {
        logActivity(actLog, joinPoint, response, false);
    }

    /**
     * Logs failed method executions after exception throw.
     *
     * @param joinPoint AspectJ join point providing access to method signature and arguments
     * @param actLog The {@link Audit} annotation from the intercepted method
     * @param response The thrown exception object
     */
    @AfterThrowing(pointcut = "@annotation(actLog)", throwing = "response")
    public void logMethodActionException(JoinPoint joinPoint, Audit actLog, Object response) {
        logActivity(actLog, joinPoint, response, true);
    }

    /**
     * Central logging handler that creates audit context and triggers logging.
     *
     * @param actLog Audit annotation metadata
     * @param joinPoint Method execution context
     * @param response Method return value or exception
     * @param exceptionThrown Flag indicating execution outcome
     */
    private void logActivity(Audit actLog, JoinPoint joinPoint, Object response, boolean exceptionThrown) {
        AuditLogClientData auditLogClientData = new AuditLogClientData(joinPoint.getArgs(), response,
                exceptionThrown);
        auditLogger.log(actLog.type(),auditLogClientData);
    }
}
