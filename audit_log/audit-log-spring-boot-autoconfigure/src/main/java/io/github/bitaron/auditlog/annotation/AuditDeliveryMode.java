package io.github.bitaron.auditlog.annotation;

/**
 * Per-{@link Audit} override of the starter-wide delivery mode
 * ({@code AuditLogProperties.DeliveryMode}).
 * <p>
 * Deliberately a distinct type from {@code AuditLogProperties.DeliveryMode} rather than reusing
 * it directly: an annotation attribute's type becomes part of every consumer's compiled bytecode,
 * and this annotation has no other reason to depend on the {@code properties} package. The extra
 * {@link #INHERIT} constant is also meaningless for the global property (there is nothing for the
 * global setting itself to inherit from), so the two enums have different valid value sets even
 * though two of the three names overlap.
 *
 * @see Audit#mode()
 */
public enum AuditDeliveryMode {

    /**
     * Use whatever {@code audit.log.mode} is configured application-wide. The default, so adding
     * this attribute to existing {@code @Audit} usages changes nothing until it's set explicitly.
     */
    INHERIT,

    /** Dispatch this audit record asynchronously, regardless of the application-wide setting. */
    ASYNC,

    /** Dispatch this audit record synchronously, regardless of the application-wide setting. */
    SYNC
}
