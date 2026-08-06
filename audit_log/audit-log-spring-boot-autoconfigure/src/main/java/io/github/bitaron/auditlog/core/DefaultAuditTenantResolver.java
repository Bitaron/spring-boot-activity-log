package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.contract.AuditTenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Default {@link AuditTenantResolver}: reads a configurable HTTP header
 * ({@code audit.log.headers.tenant-id}, default {@code X-TENANT-ID}) from the current request.
 * <p>
 * Same trust model as {@link DefaultAuditContextResolver}'s header-based actor defaults: this
 * value is spoofable by the caller unless a trusted reverse proxy sets/strips the header before
 * the request reaches this application. For a verified tenant identity, override the
 * {@link AuditTenantResolver} bean with one backed by your authentication mechanism instead (e.g.
 * a claim on the authenticated principal).
 */
public class DefaultAuditTenantResolver implements AuditTenantResolver {

    private final String tenantHeaderName;

    public DefaultAuditTenantResolver(String tenantHeaderName) {
        this.tenantHeaderName = tenantHeaderName;
    }

    @Override
    public String resolveTenantId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            return request.getHeader(tenantHeaderName);
        }
        return null;
    }
}
