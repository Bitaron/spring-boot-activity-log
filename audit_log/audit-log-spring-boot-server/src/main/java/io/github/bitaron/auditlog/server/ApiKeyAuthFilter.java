package io.github.bitaron.auditlog.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Per-tenant authentication for this module's endpoints (WP16): every request must present, via
 * the {@code X-API-Key} header, one of the secrets configured under
 * {@code audit.log.server.api-keys.<tenantId>}. A key that doesn't match any configured tenant is
 * rejected with {@code 401}; a key that matches sets which tenant the rest of the request is
 * authenticated as - stashed as a request attribute so {@link ApiKeyAuditTenantResolver} (not the
 * client) is the source of truth for tenant identity on every write and read from here on. See
 * {@link AuditLogServerProperties#apiKeys} for why this is a starting point, not a complete
 * auth solution.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    /** Request attribute {@link ApiKeyAuditTenantResolver} reads the authenticated tenant from. */
    static final String RESOLVED_TENANT_ATTRIBUTE = ApiKeyAuthFilter.class.getName() + ".RESOLVED_TENANT";

    private final Map<String, String> tenantIdByApiKey;

    /** @param tenantIdByApiKey the inverse of {@code audit.log.server.api-keys}: secret -> tenant id */
    public ApiKeyAuthFilter(Map<String, String> tenantIdByApiKey) {
        this.tenantIdByApiKey = tenantIdByApiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String suppliedApiKey = request.getHeader(API_KEY_HEADER);
        String tenantId = suppliedApiKey == null ? null : tenantIdByApiKey.get(suppliedApiKey);
        if (tenantId == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid " + API_KEY_HEADER);
            return;
        }
        request.setAttribute(RESOLVED_TENANT_ATTRIBUTE, tenantId);
        filterChain.doFilter(request, response);
    }
}
