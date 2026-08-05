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
 * {@code AuditLogAspect} matches both this container and a bare {@link Audit}, then reflects on
 * the join point's method with {@code AnnotatedElementUtils.findMergedRepeatableAnnotations} to
 * recover every declared instance - each fires its own, independently-isolated audit dispatch.
 * Method-level stacking is fully supported; type-level {@code @Audit} (applying to every method
 * of a class) remains a separate, unimplemented design question - see the type it targets in
 * {@link Audit @Target}.
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
