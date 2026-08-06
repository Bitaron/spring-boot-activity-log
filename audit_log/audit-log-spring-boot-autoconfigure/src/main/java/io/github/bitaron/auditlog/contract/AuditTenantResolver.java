package io.github.bitaron.auditlog.contract;

/**
 * Resolves the identifier of the tenant the current audit event/query belongs to.
 * <p>
 * Kept as its own SPI rather than an extra method on {@link AuditLogGenericDataGetter}: tenant
 * identity is orthogonal to actor identity (a {@code SYSTEM}-actor scheduled job still runs on
 * behalf of one tenant), so it's consulted unconditionally by
 * {@link io.github.bitaron.auditlog.core.DefaultAuditContextResolver} on every {@code @Audit}
 * invocation, regardless of {@code actorSource} - not nested inside the actor-resolution branch.
 * It is also consulted by {@link io.github.bitaron.auditlog.query.JpaAuditLogQueryService} on
 * every read, so a single implementation drives both write-time tagging and read-time scoping.
 * <p>
 * Only consulted when {@code audit.log.multi-tenancy.enabled=true} - see
 * {@link io.github.bitaron.auditlog.core.DefaultAuditTenantResolver} for the default,
 * header-based implementation registered in that case, and its javadoc for the same
 * spoofability caveat that already applies to {@link AuditLogGenericDataGetter}'s header-based
 * actor defaults. Override with a {@code @Bean} backed by verified auth (e.g. a JWT tenant claim)
 * for a trustworthy tenant identity.
 */
public interface AuditTenantResolver {

    /**
     * @return the current tenant's identifier, or {@code null}/blank if none is resolvable.
     * A write ({@link io.github.bitaron.auditlog.core.DefaultAuditContextResolver}) simply tags
     * the record with a null tenant in that case; a read
     * ({@link io.github.bitaron.auditlog.query.JpaAuditLogQueryService}) fails closed instead,
     * refusing to run an unscoped query rather than silently returning every tenant's rows.
     */
    String resolveTenantId();
}
