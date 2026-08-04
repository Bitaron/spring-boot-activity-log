package io.github.bitaron.auditLog.core;


import io.github.bitaron.auditLog.annotation.Audit;
import io.github.bitaron.auditLog.annotation.AuditIgnore;
import io.github.bitaron.auditLog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditLog.contract.AuditLogLocationResolver;
import io.github.bitaron.auditLog.dto.AuditLogClientData;
import io.github.bitaron.auditLog.properties.AuditLogProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.web.context.request.RequestContextHolder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;


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
 * <p><b>Failure isolation:</b> every step of building and dispatching the audit record is
 * wrapped in a single try/catch that only logs a warning. A failure to record an audit entry -
 * a bad template, an unserializable argument, a database error - must never turn a successful
 * (or already-failing) business call into an unrelated failure.
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
public class AuditLogAspect {

    private final AuditLogProperties auditLogProperties;
    private final AuditLogGenericDataGetter auditLogGenericDataGetter;
    private final AuditLogLocationResolver auditLogLocationResolver;
    private final AuditLogger auditLogger;

    public AuditLogAspect(AuditLogProperties auditLogProperties,
                           AuditLogGenericDataGetter auditLogGenericDataGetter,
                           AuditLogLocationResolver auditLogLocationResolver,
                           AuditLogger auditLogger) {
        this.auditLogProperties = auditLogProperties;
        this.auditLogGenericDataGetter = auditLogGenericDataGetter;
        this.auditLogLocationResolver = auditLogLocationResolver;
        this.auditLogger = auditLogger;
    }

    /**
     * Logs successful method executions after normal return.
     *
     * @param joinPoint AspectJ join point providing access to method signature and arguments
     * @param actLog    The {@link Audit} annotation from the intercepted method
     * @param response  The method's return value
     */
    @AfterReturning(pointcut = "@annotation(actLog)", returning = "response")
    public void logMethodActionSuccess(JoinPoint joinPoint, Audit actLog, Object response) {
        logActivity(actLog, joinPoint, response, false);
    }

    /**
     * Logs failed method executions after exception throw.
     *
     * @param joinPoint AspectJ join point providing access to method signature and arguments
     * @param actLog    The {@link Audit} annotation from the intercepted method
     * @param response  The thrown exception object
     */
    @AfterThrowing(pointcut = "@annotation(actLog)", throwing = "response")
    public void logMethodActionException(JoinPoint joinPoint, Audit actLog, Object response) {
        logActivity(actLog, joinPoint, response, true);
    }

    /**
     * Central logging handler that creates audit context and triggers logging.
     *
     * @param actLog          Audit annotation metadata
     * @param joinPoint       Method execution context
     * @param response        Method return value or exception
     * @param exceptionThrown Flag indicating execution outcome
     */
    private void logActivity(Audit actLog, JoinPoint joinPoint, Object response, boolean exceptionThrown) {
        try {
            if (RequestContextHolder.getRequestAttributes() == null && auditLogGenericDataGetter == null) {
                log.debug("No request context and no AuditLogGenericDataGetter configured; "
                        + "audit record for {} will have null actor/client fields", actLog.auditType());
            }
            Object args = buildArgs(joinPoint);
            AuditLogClientData auditLogClientData = new AuditLogClientData(
                    actLog, args, response, exceptionThrown,
                    this.auditLogGenericDataGetter, this.auditLogProperties, this.auditLogLocationResolver);
            auditLogger.log(actLog, auditLogClientData);
        } catch (Exception e) {
            log.warn("Failed to record audit log for {}#{}", joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName(), e);
        }
    }

    /**
     * Replaces the value of any parameter annotated {@link AuditIgnore} with a placeholder before
     * it reaches serialization, and preserves the historical behavior of storing a single
     * argument directly rather than wrapped in a one-element array.
     */
    private Object buildArgs(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return null;
        }
        boolean[] ignored = resolveIgnoredParameters(joinPoint, args.length);
        Object[] filtered = args;
        for (int i = 0; i < args.length; i++) {
            if (ignored[i]) {
                if (filtered == args) {
                    filtered = args.clone();
                }
                filtered[i] = "***ignored***";
            }
        }
        return filtered.length == 1 ? filtered[0] : filtered;
    }

    private boolean[] resolveIgnoredParameters(JoinPoint joinPoint, int argCount) {
        boolean[] ignored = new boolean[argCount];
        if (!(joinPoint.getSignature() instanceof MethodSignature methodSignature)) {
            return ignored;
        }
        Method method = methodSignature.getMethod();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (int i = 0; i < Math.min(argCount, parameterAnnotations.length); i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof AuditIgnore) {
                    ignored[i] = true;
                    break;
                }
            }
        }
        return ignored;
    }
}
