package io.github.bitaron.auditlog.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Owns the {@link Server} instance's lifecycle - starts it in {@link #afterPropertiesSet()}, stops
 * it in {@link #destroy()}. Not {@code @Scheduled}/{@code @EnableScheduling}-style framework
 * magic, and not a third-party {@code grpc-spring-boot-starter} dependency either: a plain
 * {@link InitializingBean}/{@link DisposableBean}, the same hand-rolled lifecycle pattern
 * {@code AuditLogRetentionService} and {@code AuditLogTaskExecutor} already use in the core
 * starter, for the same reason - this project writes its own auto-configuration rather than taking
 * on community glue dependencies for something this small.
 */
@Slf4j
public class AuditLogGrpcServer implements InitializingBean, DisposableBean {

    private final int port;
    private final AuditLogGrpcService service;
    private final ApiKeyGrpcServerInterceptor apiKeyInterceptor;
    private Server server;

    public AuditLogGrpcServer(int port, AuditLogGrpcService service, ApiKeyGrpcServerInterceptor apiKeyInterceptor) {
        this.port = port;
        this.service = service;
        this.apiKeyInterceptor = apiKeyInterceptor;
    }

    @Override
    public void afterPropertiesSet() throws IOException {
        server = ServerBuilder.forPort(port)
                .addService(service)
                .intercept(apiKeyInterceptor)
                .build()
                .start();
        log.info("audit-log gRPC server started on port {}", getPort());
    }

    /**
     * The actual bound port - only different from the configured {@code audit.log.grpc.port} when
     * that was {@code 0} (OS-assigned ephemeral port, e.g. {@code audit.log.grpc.port=0} in tests
     * that need a free port rather than a fixed one).
     */
    public int getPort() {
        return server.getPort();
    }

    @Override
    public void destroy() throws InterruptedException {
        if (server == null) {
            return;
        }
        server.shutdown();
        if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warn("audit-log gRPC server did not terminate gracefully within 30s; forcing shutdown");
            server.shutdownNow();
        }
    }
}
