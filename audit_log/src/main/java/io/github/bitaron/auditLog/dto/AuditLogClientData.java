package io.github.bitaron.auditLog.dto;

import io.github.bitaron.auditLog.annotation.Audit;
import io.github.bitaron.auditLog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditLog.contract.AuditLogLocationResolver;
import io.github.bitaron.auditLog.properties.AuditLogProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Represents client-specific execution context data for audit logging purposes.
 * <p>
 * This class encapsulates runtime information about an audited method invocation, including:
 * <ul>
 *   <li>Method arguments passed to the audited method</li>
 *   <li>Return value or exception produced by the method</li>
 *   <li>Actor/initiator identifier for the operation</li>
 *   <li>Execution status (success/exception)</li>
 * </ul>
 *
 * <p>Instances are typically created by the audit framework during method interception and
 * passed to {@link io.github.bitaron.auditLog.contract.AuditLogTemplateResolver#resolveTemplate}
 * for audit log message generation.
 *
 * <p><b>Trust model:</b> when no {@link AuditLogGenericDataGetter} bean is configured, the actor
 * identity is read from client-supplied HTTP headers (see
 * {@link AuditLogProperties#getHeaderFor(String)}), and the client IP is trusted from
 * {@code X-Forwarded-For}-style headers only when {@code AuditLogProperties.trustForwardedHeaders}
 * is explicitly enabled. Both are spoofable by the caller unless a trusted reverse proxy
 * strips/overwrites them before the request reaches this application. For a verified actor
 * identity, supply an {@link AuditLogGenericDataGetter} backed by your authentication mechanism
 * (e.g. {@code SecurityContextHolder}) instead of relying on the header defaults.
 *
 * <p><b>Thread Safety:</b> This class is not thread-safe and should only be used within
 * the context of a single method invocation.
 *
 * @see io.github.bitaron.auditLog.annotation.Audit
 * @see io.github.bitaron.auditLog.contract.AuditLogTemplateResolver
 */
@Slf4j
@Data
public class AuditLogClientData {
    /**
     * The identifier of the actor (user or system) initiating the action.
     * May be {@code null} if not applicable or unavailable.
     */
    private String actorId;
    private String actorName;
    private String clientLocation;
    private String clientIp;
    private String userAgent;


    /**
     * The arguments passed to the audited method. Preserved as:
     * <ul>
     *   <li>Single argument: the argument object directly</li>
     *   <li>Multiple arguments: object array containing all parameters</li>
     *   <li>Zero arguments: {@code null} or empty array</li>
     * </ul>
     */
    private Object args;

    /**
     * The successful method return value. Will be {@code null} when:
     * <ul>
     *   <li>The method has a {@code void} return type</li>
     *   <li>An exception occurred during execution</li>
     * </ul>
     */
    private Object response;

    /**
     * Flag indicating whether an exception occurred during method execution.
     * When {@code true}, the {@link #exception} field contains the throwable.
     */
    private boolean exceptionThrown;

    /**
     * The exception thrown during method execution, if any.
     * Always {@code null} when {@link #exceptionThrown} is {@code false}.
     */
    private Object exception;

    /**
     * Constructs an audit log context container with execution results.
     *
     * @param args            The method parameters received by the audited method.
     *                        May be {@code null}, a single object, or an object array.
     * @param response        The method return value when no exception occurred,
     *                        or the exception object when {@code exceptionThrown} is {@code true}.
     *                        May be {@code null} for void methods or unthrown exceptions.
     * @param exceptionThrown {@code true} if the method terminated with an exception,
     *                        {@code false} for successful execution
     */
    public AuditLogClientData(Audit audit, Object args, Object response, boolean exceptionThrown,
                               AuditLogGenericDataGetter auditLogGenericDataGetter,
                               AuditLogProperties auditLogProperties,
                               AuditLogLocationResolver auditLogLocationResolver) {
        if (audit.isActorSystem()) {
            this.actorId = "SYSTEM";
            this.actorName = "SYSTEM";
        } else if (audit.isActorCommon()) {
            resolveCommonActor(auditLogGenericDataGetter, auditLogProperties, auditLogLocationResolver);
        } else if (response instanceof AuditLogGenericDataGetter dataGetter) {
            this.clientIp = dataGetter.getClientIp();
            this.userAgent = dataGetter.getUserAgent();
            this.clientLocation = dataGetter.getClientLocation();
            this.actorId = dataGetter.getActorId();
            this.actorName = dataGetter.getActorName();
        } else {
            log.warn("@Audit(isActorCommon=false) but the method's return value does not implement "
                    + "AuditLogGenericDataGetter; actor fields will be null for this audit record");
        }
        this.args = args;
        this.exceptionThrown = exceptionThrown;
        if (!exceptionThrown) {
            this.response = response;
        } else {
            this.exception = response;
        }
    }

    private void resolveCommonActor(AuditLogGenericDataGetter auditLogGenericDataGetter,
                                     AuditLogProperties auditLogProperties,
                                     AuditLogLocationResolver auditLogLocationResolver) {
        if (auditLogGenericDataGetter != null) {
            this.clientIp = auditLogGenericDataGetter.getClientIp();
            this.userAgent = auditLogGenericDataGetter.getUserAgent();
            this.clientLocation = auditLogGenericDataGetter.getClientLocation();
            this.actorId = auditLogGenericDataGetter.getActorId();
            this.actorName = auditLogGenericDataGetter.getActorName();
            return;
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            this.clientIp = getClientIP(request, auditLogProperties.isTrustForwardedHeaders());
            this.userAgent = request.getHeader("User-Agent");
            this.clientLocation = auditLogLocationResolver != null
                    ? auditLogLocationResolver.resolveLocation(this.clientIp) : null;
            this.actorId = request.getHeader(auditLogProperties.getHeaderFor(AuditLogProperties.REQUESTER_ID));
            this.actorName = request.getHeader(auditLogProperties.getHeaderFor(AuditLogProperties.REQUESTER_NAME));
            return;
        }
        log.warn("No AuditLogGenericDataGetter bean and no HTTP request context available; "
                + "actor/client fields will be null for this audit record");
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
}
