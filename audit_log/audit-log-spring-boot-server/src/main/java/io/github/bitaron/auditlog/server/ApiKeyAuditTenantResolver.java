package io.github.bitaron.auditlog.server;

import io.github.bitaron.auditlog.contract.AuditTenantResolver;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The server module's {@link AuditTenantResolver} (WP16): returns whichever tenant
 * {@link ApiKeyAuthFilter} authenticated the current request as, never a client-suppliable value.
 * <p>
 * This is what closes the gap the core starter's header-based
 * {@code io.github.bitaron.auditlog.core.DefaultAuditTenantResolver} default cannot on its own:
 * a header is just data the caller sends and could set to any value, but the tenant this resolver
 * returns is exactly the one whose API key was actually presented and verified - a caller cannot
 * read or write as a tenant it doesn't hold a key for, no matter what it puts in a header or
 * request body.
 * <p>
 * Registered by {@code AuditLogServerAutoConfiguration} ahead of the core starter's default (see
 * its {@code @AutoConfigureBefore}), so it's the {@link AuditTenantResolver} bean every
 * {@code DefaultAuditContextResolver}/{@code JpaAuditLogQueryService} in this application resolves
 * against once the server module is enabled - including for requests that never go through this
 * module's controllers, e.g. a host application's own {@code @Audit}-annotated methods running in
 * the same process. That's intentional: there is exactly one tenant identity mechanism active per
 * application, not two that could disagree.
 */
public class ApiKeyAuditTenantResolver implements AuditTenantResolver {

    @Override
    public String resolveTenantId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes) {
            Object tenantId = servletAttributes.getRequest().getAttribute(ApiKeyAuthFilter.RESOLVED_TENANT_ATTRIBUTE);
            return tenantId instanceof String tenantIdString ? tenantIdString : null;
        }
        return null;
    }
}
