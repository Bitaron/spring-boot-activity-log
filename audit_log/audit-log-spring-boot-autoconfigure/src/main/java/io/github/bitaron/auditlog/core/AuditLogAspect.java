package io.github.bitaron.auditlog.core;


import io.github.bitaron.auditlog.annotation.ActorSource;
import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.annotation.AuditIgnore;
import io.github.bitaron.auditlog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditlog.contract.AuditLogLocationResolver;
import io.github.bitaron.auditlog.dto.AuditLogClientData;
import io.github.bitaron.auditlog.properties.AuditLogProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.web.context.request.RequestContextHolder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


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

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    // Parsing a SpEL expression walks and compiles its AST; like the FreeMarker template cache,
    // re-parsing the same actorExpression string on every invocation of the same method is
    // wasted work. Keyed by Method (matches Spring's own CacheOperationExpressionEvaluator
    // pattern) - unbounded is acceptable here since the key space is bounded by the number of
    // distinct @Audit-annotated methods in the application, not by runtime data.
    private final Map<Method, Expression> actorExpressionCache = new ConcurrentHashMap<>();

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
            String expressionActor = resolveActorExpression(actLog, joinPoint, response, exceptionThrown);
            AuditLogClientData auditLogClientData = new AuditLogClientData(
                    actLog, args, response, exceptionThrown,
                    this.auditLogGenericDataGetter, this.auditLogProperties, this.auditLogLocationResolver,
                    expressionActor);
            auditLogger.log(actLog, auditLogClientData);
        } catch (Exception e) {
            log.warn("Failed to record audit log for {}#{}", joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName(), e);
        }
    }

    /**
     * Evaluates {@link Audit#actorExpression()} when {@link Audit#actorSource()} is
     * {@link ActorSource#EXPRESSION}, exposing {@code #result}, {@code #args}, and
     * {@code #exception} as SpEL variables. Returns {@code null} for any other actor source, or
     * if evaluation fails - a broken expression must not break the audited call, so failures are
     * logged and treated the same as "no actor available" rather than propagated.
     */
    private String resolveActorExpression(Audit actLog, JoinPoint joinPoint, Object response, boolean exceptionThrown) {
        if (actLog.actorSource() != ActorSource.EXPRESSION || actLog.actorExpression().isEmpty()) {
            return null;
        }
        if (!(joinPoint.getSignature() instanceof MethodSignature methodSignature)) {
            log.warn("@Audit(actorSource=EXPRESSION) is only supported on ordinary method join points; "
                    + "actor fields will be null for this audit record");
            return null;
        }
        try {
            Method method = methodSignature.getMethod();
            Expression expression = actorExpressionCache.computeIfAbsent(
                    method, m -> expressionParser.parseExpression(actLog.actorExpression()));
            EvaluationContext context = new MethodBasedEvaluationContext(
                    joinPoint.getTarget(), method, joinPoint.getArgs(), parameterNameDiscoverer);
            context.setVariable("result", exceptionThrown ? null : response);
            context.setVariable("args", joinPoint.getArgs());
            context.setVariable("exception", exceptionThrown ? response : null);
            Object value = expression.getValue(context);
            return value != null ? String.valueOf(value) : null;
        } catch (Exception e) {
            log.warn("Failed to evaluate actorExpression \"{}\" for {}#{}; actor fields will be null for this audit record",
                    actLog.actorExpression(), joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName(), e);
            return null;
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
