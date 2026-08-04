package io.github.bitaron.auditlog.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a parameter of an {@link Audit @Audit}-annotated method as excluded from the persisted
 * audit record. The argument's position is preserved but its value is replaced with a
 * placeholder before serialization - use this for request/response objects, large payloads, or
 * anything that shouldn't be captured verbatim even after the default type- and field-name-based
 * filtering.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AuditIgnore {
}
