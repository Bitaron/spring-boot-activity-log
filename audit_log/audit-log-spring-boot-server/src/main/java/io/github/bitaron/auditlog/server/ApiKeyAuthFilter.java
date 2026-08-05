package io.github.bitaron.auditlog.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * First-cut authentication for this module's endpoints: requires the configured
 * {@code audit.log.server.api-key} value on every request via the {@code X-API-Key} header,
 * rejecting anything else with {@code 401}. See {@link AuditLogServerProperties#apiKey} for why
 * this is a starting point, not a complete auth solution - production deployments should front
 * this module with real authn/authz.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final String expectedApiKey;

    public ApiKeyAuthFilter(String expectedApiKey) {
        this.expectedApiKey = expectedApiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String suppliedApiKey = request.getHeader(API_KEY_HEADER);
        if (!expectedApiKey.equals(suppliedApiKey)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid " + API_KEY_HEADER);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
