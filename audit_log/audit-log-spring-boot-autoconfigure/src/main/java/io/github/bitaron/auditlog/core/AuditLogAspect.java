package io.github.bitaron.auditlog.core;


import io.github.bitaron.auditlog.annotation.ActorSource;
import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.annotation.AuditIgnore;
import io.github.bitaron.auditlog.model.AuditContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

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
 *   <li>Times and invokes the audited method, capturing its arguments, outcome, and duration</li>
 *   <li>Constructs audit context data using {@link AuditContext}</li>
 *   <li>Delegates logging operations to {@link AuditLogger}</li>
 * </ol>
 * <p>
 * Implemented as a single {@link #logMethodAction} {@code @Around} advice, rather than separate
 * {@code @AfterReturning}/{@code @AfterThrowing} advice, specifically so duration can be measured
 * around the call - that is not observable from either after-advice alone.
 * <p>
 * Actor/client resolution is delegated to {@link AuditContextResolver} - this aspect only
 * captures what only AOP can see (arguments, return value, exception, duration, the join point)
 * and evaluates {@link Audit#actorExpression()}.
 *
 * <p><b>Ordering:</b> explicitly ordered one step ahead of {@link Ordered#LOWEST_PRECEDENCE} -
 * the default order Spring gives the {@code @Transactional} advisor when
 * {@code @EnableTransactionManagement} doesn't set one explicitly. Per Spring's advice-ordering
 * rule ("on the way in, highest precedence runs first; on the way out, highest precedence runs
 * last" - i.e. highest precedence is the outermost layer), that guarantees this aspect wraps
 * outside the transactional advice rather than tying with it at the same default order (an
 * undefined relative order). Wrapping outside means this aspect only builds and dispatches the
 * audit record once the audited method's own transaction has already committed or rolled back -
 * so for a directly-annotated {@code @Transactional @Audit} method, the record reflects a
 * decision that has already been made, rather than one still pending. The commit-aware deferred
 * dispatch in {@link AuditLogger} still matters for the remaining case: an audited method called
 * from within a larger transaction further up the call stack that is still open when this
 * advice's audit logic runs.
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
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class AuditLogAspect {

    private final AuditContextResolver auditContextResolver;
    private final AuditLogger auditLogger;

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    // Parsing a SpEL expression walks and compiles its AST; like the FreeMarker template cache,
    // re-parsing the same actorExpression string on every invocation of the same method is
    // wasted work. Keyed by Method (matches Spring's own CacheOperationExpressionEvaluator
    // pattern) - unbounded is acceptable here since the key space is bounded by the number of
    // distinct @Audit-annotated methods in the application, not by runtime data.
    private final Map<Method, Expression> actorExpressionCache = new ConcurrentHashMap<>();

    public AuditLogAspect(AuditContextResolver auditContextResolver, AuditLogger auditLogger) {
        this.auditContextResolver = auditContextResolver;
        this.auditLogger = auditLogger;
    }

    /**
     * Invokes the audited method, timing it and recording its outcome regardless of whether it
     * returns normally or throws. The thrown exception (if any) always propagates to the caller
     * unchanged - recording a failed attempt must never itself change what the caller sees.
     *
     * @param joinPoint AspectJ join point providing access to method signature, arguments, and
     *                  the ability to proceed with the actual invocation
     * @param actLog    The {@link Audit} annotation from the intercepted method
     * @return whatever the audited method returns
     * @throws Throwable whatever the audited method throws, unchanged
     */
    @Around("@annotation(actLog)")
    public Object logMethodAction(ProceedingJoinPoint joinPoint, Audit actLog) throws Throwable {
        long startNanos = System.nanoTime();
        Object result = null;
        Throwable thrown = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
            logActivity(actLog, joinPoint, thrown != null ? thrown : result, thrown != null, durationMillis);
        }
    }

    /**
     * Central logging handler that creates audit context and triggers logging.
     *
     * @param actLog          Audit annotation metadata
     * @param joinPoint       Method execution context
     * @param response        Method return value or exception
     * @param exceptionThrown Flag indicating execution outcome
     * @param durationMillis  How long the audited method took to execute (or throw)
     */
    private void logActivity(Audit actLog, ProceedingJoinPoint joinPoint, Object response, boolean exceptionThrown,
                              long durationMillis) {
        try {
            Object args = buildArgs(joinPoint);
            String expressionActor = resolveActorExpression(actLog, joinPoint, response, exceptionThrown);
            AuditContext auditContext = auditContextResolver.resolve(
                    actLog, args, response, exceptionThrown, expressionActor, durationMillis);
            auditLogger.log(actLog, auditContext);
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
    private String resolveActorExpression(Audit actLog, ProceedingJoinPoint joinPoint, Object response, boolean exceptionThrown) {
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
    private Object buildArgs(ProceedingJoinPoint joinPoint) {
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

    private boolean[] resolveIgnoredParameters(ProceedingJoinPoint joinPoint, int argCount) {
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
