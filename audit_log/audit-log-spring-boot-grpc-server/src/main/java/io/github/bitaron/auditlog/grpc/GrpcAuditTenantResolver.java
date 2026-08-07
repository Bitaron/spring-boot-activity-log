package io.github.bitaron.auditlog.grpc;

import io.github.bitaron.auditlog.contract.AuditTenantResolver;

/**
 * The gRPC module's {@link AuditTenantResolver}: returns whichever tenant
 * {@link ApiKeyGrpcServerInterceptor} authenticated the current call as, read from the gRPC
 * {@link io.grpc.Context} - never a caller-suppliable value. Mirrors
 * {@code ApiKeyAuditTenantResolver} in the REST server module exactly, one level down at the
 * transport layer (gRPC {@code Context} instead of an {@code HttpServletRequest} attribute) - see
 * that class's javadoc for why this is what actually closes the gap a client-suppliable header
 * can't close on its own.
 * <p>
 * Registered by {@link AuditLogGrpcServerAutoConfiguration} ahead of the core starter's default
 * (see its {@code @AutoConfigureBefore}), so it's the {@link AuditTenantResolver} bean every
 * {@code DefaultAuditContextResolver}/{@code JpaAuditLogQueryService} in the application resolves
 * against once this module is enabled - see that autoconfiguration's javadoc for why this module
 * cannot coexist with the REST server module in the same application.
 */
public class GrpcAuditTenantResolver implements AuditTenantResolver {

    @Override
    public String resolveTenantId() {
        return ApiKeyGrpcServerInterceptor.TENANT_CONTEXT_KEY.get();
    }
}
