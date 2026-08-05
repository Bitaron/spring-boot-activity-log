package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.annotation.AuditDeliveryMode;
import io.github.bitaron.auditlog.contract.AuditMetricsRecorder;
import io.github.bitaron.auditlog.model.AuditContext;
import io.github.bitaron.auditlog.properties.AuditLogProperties.DeliveryMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Dispatches audit records to {@link AuditLogWriter} according to the configured
 * {@link DeliveryMode}.
 * <p>
 * <b>{@code SYNC}</b> calls {@link AuditLogWriter#persistShared} directly on the caller's thread,
 * sharing the caller's transaction: the audit record commits and rolls back atomically with the
 * business operation it describes.
 * <p>
 * <b>{@code ASYNC}</b> (default) dispatches to a dedicated executor so the audited method isn't
 * slowed down by the write, calling {@link AuditLogWriter#persistRequiresNew}. Naively dispatching
 * immediately would let a record commit for an operation whose surrounding transaction later
 * rolls back - which previously happened unconditionally, since dispatch always fired from
 * {@code @AfterReturning}/{@code @AfterThrowing} advice before the caller's transaction had a
 * chance to commit or roll back. If a transaction is active when {@link #log} is called, dispatch
 * is deferred to {@link TransactionSynchronization#afterCommit()} instead; with no active
 * transaction, dispatch happens immediately since there's nothing to wait for.
 * <p>
 * Every place a record can be lost - the executor's queue is full, the write itself fails - is
 * reported through {@link AuditMetricsRecorder} in addition to being logged, so delivery loss is
 * an observable, alertable signal rather than something that only shows up in application logs.
 * <p>
 * <b>Per-call override:</b> {@link Audit#mode()} lets one {@code @Audit} site force {@code SYNC}
 * or {@code ASYNC} regardless of the application-wide {@link #mode}; {@link #effectiveMode}
 * resolves the two into the single mode actually used for a given record.
 */
@Slf4j
public class AuditLogger {

    private final AuditLogWriter auditLogWriter;
    private final Executor auditLogTaskExecutor;
    private final AuditMetricsRecorder metrics;
    private final DeliveryMode mode;

    public AuditLogger(AuditLogWriter auditLogWriter, Executor auditLogTaskExecutor,
                        AuditMetricsRecorder metrics, DeliveryMode mode) {
        this.auditLogWriter = auditLogWriter;
        this.auditLogTaskExecutor = auditLogTaskExecutor;
        this.metrics = metrics;
        this.mode = mode;
    }

    public void log(Audit audit, AuditContext auditContext) {
        if (effectiveMode(audit) == DeliveryMode.SYNC) {
            writeShared(audit, auditContext);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(audit, auditContext);
                }
            });
        } else {
            dispatch(audit, auditContext);
        }
    }

    /**
     * Resolves {@link Audit#mode()} against the application-wide {@link #mode}: an explicit
     * {@code ASYNC}/{@code SYNC} on the annotation always wins; {@link AuditDeliveryMode#INHERIT}
     * (the default) falls back to whatever {@code audit.log.mode} is configured.
     */
    private DeliveryMode effectiveMode(Audit audit) {
        return switch (audit.mode()) {
            case ASYNC -> DeliveryMode.ASYNC;
            case SYNC -> DeliveryMode.SYNC;
            case INHERIT -> mode;
        };
    }

    private void writeShared(Audit audit, AuditContext auditContext) {
        try {
            auditLogWriter.persistShared(audit, auditContext);
            metrics.recordWritten();
        } catch (Exception e) {
            metrics.recordFailed();
            log.warn("Failed to persist audit log entry for auditType={}", audit.auditType(), e);
        }
    }

    private void dispatch(Audit audit, AuditContext auditContext) {
        try {
            auditLogTaskExecutor.execute(() -> {
                try {
                    auditLogWriter.persistRequiresNew(audit, auditContext);
                    metrics.recordWritten();
                } catch (Exception e) {
                    metrics.recordFailed();
                    log.warn("Failed to persist audit log entry for auditType={}", audit.auditType(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            metrics.recordRejected();
            log.warn("Audit log delivery queue is full; dropping audit log entry for auditType={}",
                    audit.auditType(), e);
        }
    }
}
