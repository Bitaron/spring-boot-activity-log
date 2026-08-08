package io.github.bitaron.auditlog.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.util.Map;

/**
 * Per-tenant authentication for every RPC on {@link AuditLogGrpcService} - the gRPC equivalent of
 * {@code ApiKeyAuthFilter} in the REST server module. Every call must present, via the
 * {@code x-api-key} metadata entry, one of the secrets configured under
 * {@code audit.log.grpc.api-keys.<tenantId>}. A key that doesn't match any configured tenant is
 * rejected with {@link Status#UNAUTHENTICATED}; a key that matches sets which tenant the rest of
 * the call is authenticated as into a {@link Context} value, which {@link GrpcAuditTenantResolver}
 * (not the caller) is the source of truth for from then on.
 */
public class ApiKeyGrpcServerInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> API_KEY_METADATA_KEY =
            Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER);

    /** The authenticated tenant for the current call - see {@link GrpcAuditTenantResolver}. */
    static final Context.Key<String> TENANT_CONTEXT_KEY = Context.key("audit-log-tenant-id");

    private final Map<String, String> tenantIdByApiKey;

    /** @param tenantIdByApiKey the inverse of {@code audit.log.grpc.api-keys}: secret -> tenant id */
    public ApiKeyGrpcServerInterceptor(Map<String, String> tenantIdByApiKey) {
        this.tenantIdByApiKey = tenantIdByApiKey;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String suppliedApiKey = headers.get(API_KEY_METADATA_KEY);
        String tenantId = suppliedApiKey == null ? null : tenantIdByApiKey.get(suppliedApiKey);
        if (tenantId == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid x-api-key"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        Context context = Context.current().withValue(TENANT_CONTEXT_KEY, tenantId);
        return Contexts.interceptCall(context, call, headers, next);
    }
}
