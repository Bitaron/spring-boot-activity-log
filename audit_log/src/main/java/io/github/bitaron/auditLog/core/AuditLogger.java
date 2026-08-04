package io.github.bitaron.auditLog.core;

import io.github.bitaron.auditLog.annotation.Audit;
import io.github.bitaron.auditLog.dto.AuditLogClientData;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;

/**
 * Dispatches audit records to {@link AuditLogWriter} on a dedicated executor, off the caller's
 * thread and outside the caller's transaction.
 * <p>
 * Deliberately not built on {@code @Async}: the previous implementation relied on it, but this
 * class was constructed with {@code new} inside the aspect rather than as a Spring bean, so the
 * annotation was never actually proxied and every write ran synchronously on the caller's
 * thread. Submitting to an explicit {@link Executor} here is simpler and doesn't require the
 * consuming application to have {@code @EnableAsync} turned on.
 */
@Slf4j
public class AuditLogger {

    private final AuditLogWriter auditLogWriter;
    private final Executor auditLogTaskExecutor;

    public AuditLogger(AuditLogWriter auditLogWriter, Executor auditLogTaskExecutor) {
        this.auditLogWriter = auditLogWriter;
        this.auditLogTaskExecutor = auditLogTaskExecutor;
    }

    public void log(Audit audit, AuditLogClientData clientData) {
        auditLogTaskExecutor.execute(() -> {
            try {
                auditLogWriter.persist(audit, clientData);
            } catch (Exception e) {
                log.warn("Failed to persist audit log entry for auditType={}", audit.auditType(), e);
            }
        });
    }
}
