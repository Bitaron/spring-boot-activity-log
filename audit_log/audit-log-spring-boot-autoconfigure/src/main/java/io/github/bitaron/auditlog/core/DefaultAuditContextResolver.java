package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditlog.contract.AuditLogLocationResolver;
import io.github.bitaron.auditlog.model.AuditContext;
import io.github.bitaron.auditlog.properties.AuditLogProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Default {@link AuditContextResolver}: resolves the actor per {@link Audit#actorSource()} and,
 * for {@link io.github.bitaron.auditlog.annotation.ActorSource#CONTEXT}, the client IP/user-agent
 * from the current HTTP request.
 * <p>
 * <b>Trust model:</b> when no {@link AuditLogGenericDataGetter} bean is configured, the actor
 * identity is read from client-supplied HTTP headers (see {@link AuditLogProperties.Headers}),
 * and the client IP is trusted from
 * {@code X-Forwarded-For}-style headers only when {@code AuditLogProperties.trustForwardedHeaders}
 * is explicitly enabled. Both are spoofable by the caller unless a trusted reverse proxy
 * strips/overwrites them before the request reaches this application. For a verified actor
 * identity, supply an {@link AuditLogGenericDataGetter} backed by your authentication mechanism
 * (e.g. {@code SecurityContextHolder}) instead of relying on the header defaults.
 * <p>
 * This class is the one place in the starter permitted to read ambient request state, which is
 * what makes it unit-testable with a {@code MockHttpServletRequest} and nothing else - unlike the
 * pre-2.0 design, where this same lookup happened inside a DTO constructor.
 */
@Slf4j
public class DefaultAuditContextResolver implements AuditContextResolver {

    private final AuditLogGenericDataGetter auditLogGenericDataGetter;
    private final AuditLogProperties auditLogProperties;
    private final AuditLogLocationResolver auditLogLocationResolver;

    public DefaultAuditContextResolver(AuditLogGenericDataGetter auditLogGenericDataGetter,
                                        AuditLogProperties auditLogProperties,
                                        AuditLogLocationResolver auditLogLocationResolver) {
        this.auditLogGenericDataGetter = auditLogGenericDataGetter;
        this.auditLogProperties = auditLogProperties;
        this.auditLogLocationResolver = auditLogLocationResolver;
    }

    @Override
    public AuditContext resolve(Audit audit, Object args, Object result, boolean exceptionThrown,
                                 String expressionActorValue, long durationMillis) {
        String actorId = null;
        String actorName = null;
        String clientIp = null;
        String clientLocation = null;
        String userAgent = null;

        switch (audit.actorSource()) {
            case SYSTEM -> {
                actorId = "SYSTEM";
                actorName = "SYSTEM";
            }
            case EXPRESSION -> {
                actorId = expressionActorValue;
                actorName = expressionActorValue;
                if (expressionActorValue == null) {
                    log.warn("@Audit(actorSource=EXPRESSION) but actorExpression evaluated to null; "
                            + "actor fields will be null for this audit record");
                }
            }
            case CONTEXT -> {
                CommonActor commonActor = resolveCommonActor();
                actorId = commonActor.actorId;
                actorName = commonActor.actorName;
                clientIp = commonActor.clientIp;
                clientLocation = commonActor.clientLocation;
                userAgent = commonActor.userAgent;
            }
        }

        return new AuditContext(actorId, actorName, clientLocation, clientIp, userAgent,
                args, exceptionThrown ? null : result, exceptionThrown ? result : null, exceptionThrown,
                durationMillis, MDC.get("traceId"));
    }

    private CommonActor resolveCommonActor() {
        if (auditLogGenericDataGetter != null) {
            return new CommonActor(
                    auditLogGenericDataGetter.getActorId(),
                    auditLogGenericDataGetter.getActorName(),
                    auditLogGenericDataGetter.getClientIp(),
                    auditLogGenericDataGetter.getClientLocation(),
                    auditLogGenericDataGetter.getUserAgent());
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            String clientIp = getClientIP(request, auditLogProperties.isTrustForwardedHeaders());
            String userAgent = request.getHeader("User-Agent");
            String clientLocation = auditLogLocationResolver != null
                    ? auditLogLocationResolver.resolveLocation(clientIp) : null;
            String actorId = request.getHeader(auditLogProperties.getHeaders().getRequesterId());
            String actorName = request.getHeader(auditLogProperties.getHeaders().getRequesterName());
            return new CommonActor(actorId, actorName, clientIp, clientLocation, userAgent);
        }
        log.warn("No AuditLogGenericDataGetter bean and no HTTP request context available; "
                + "actor/client fields will be null for this audit record");
        return new CommonActor(null, null, null, null, null);
    }

    // Get client IP (handles proxies like Nginx or Cloudflare) - only when explicitly trusted,
    // since these headers are otherwise attacker-controlled (see class javadoc "Trust model").
    private String getClientIP(HttpServletRequest request, boolean trustForwardedHeaders) {
        if (trustForwardedHeaders) {
            String ip = firstNonBlank(request.getHeader("X-Forwarded-For"),
                    request.getHeader("Proxy-Client-IP"), request.getHeader("WL-Proxy-Client-IP"));
            if (ip != null) {
                return ip.split(",")[0].trim(); // Handle multiple IPs in X-Forwarded-For
            }
        }
        return request.getRemoteAddr();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return null;
    }

    private record CommonActor(String actorId, String actorName, String clientIp,
                                String clientLocation, String userAgent) {
    }
}
