package io.github.bitaron.auditlog.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container for repeated {@link Audit} annotations, per the standard {@code @Repeatable} pattern.
 * You do not use this annotation directly - the compiler generates it when you place more than
 * one {@link Audit} on the same method or type.
 * <p>
 * <b>Current limitation:</b> the aspect ({@code AuditLogAspect}) binds to {@link Audit} via
 * AspectJ's {@code @annotation()} pointcut designator, which matches a single annotation
 * instance. Multiple {@code @Audit} on one method are valid to declare, but only the first is
 * currently processed - full per-instance firing is planned but not yet implemented.
 *
 * @since 2.0
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Audits {
    Audit[] value();
}
