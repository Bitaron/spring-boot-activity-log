package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.contract.AuditMetricsRecorder;
import io.github.bitaron.auditlog.model.AuditContext;
import io.github.bitaron.auditlog.properties.AuditLogProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit-level coverage of {@link AuditLogger}'s dispatch logic: which delivery mode calls what,
 * and when. Uses a directly-executing {@link java.util.concurrent.Executor} and Mockito mocks
 * rather than a full Spring context, so the commit-deferral behavior can be driven precisely via
 * {@link TransactionSynchronizationManager} without a real transaction manager or database - see
 * {@code AuditLogWriterTest} for the full-stack version of the same guarantee against a real JPA
 * transaction.
 */
class AuditLoggerTest {

    private final AuditLogWriter writer = mock(AuditLogWriter.class);
    private final AuditMetricsRecorder metrics = mock(AuditMetricsRecorder.class);
    private final Audit audit = fixtureAudit();
    private final AuditContext clientData = new AuditContext(
            null, null, null, null, null, null, null, null, false, 0, null);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void asyncModeWithNoActiveTransactionDispatchesImmediately() {
        AuditLogger logger = new AuditLogger(writer, Runnable::run, metrics, AuditLogProperties.DeliveryMode.ASYNC);

        logger.log(audit, clientData);

        verify(writer, times(1)).persistRequiresNew(audit, clientData);
        verify(metrics).recordWritten();
    }

    @Test
    void asyncModeWithActiveTransactionDefersDispatchUntilAfterCommit() {
        AuditLogger logger = new AuditLogger(writer, Runnable::run, metrics, AuditLogProperties.DeliveryMode.ASYNC);

        TransactionSynchronizationManager.initSynchronization();
        logger.log(audit, clientData);

        // Not dispatched yet - the writer must not have been touched before commit.
        verifyNoInteractions(writer);

        // Simulate the transaction committing.
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verify(writer, times(1)).persistRequiresNew(audit, clientData);
        verify(metrics).recordWritten();
    }

    /**
     * The direct regression test for A1: if the transaction rolls back instead of committing,
     * Spring calls {@code afterCompletion(STATUS_ROLLED_BACK)} on registered synchronizations,
     * never {@code afterCommit()} - so the write this test registered is simply never dispatched,
     * with no special rollback-handling code needed in AuditLogger itself.
     */
    @Test
    void asyncModeNeverDispatchesWhenTransactionRollsBack() {
        AuditLogger logger = new AuditLogger(writer, Runnable::run, metrics, AuditLogProperties.DeliveryMode.ASYNC);

        TransactionSynchronizationManager.initSynchronization();
        logger.log(audit, clientData);

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verifyNoInteractions(writer);
        verifyNoInteractions(metrics);
    }

    @Test
    void syncModeWritesImmediatelyOnCallerThreadRegardlessOfTransactionState() {
        AuditLogger logger = new AuditLogger(writer, Runnable::run, metrics, AuditLogProperties.DeliveryMode.SYNC);

        logger.log(audit, clientData);

        verify(writer, times(1)).persistShared(audit, clientData);
        verify(metrics).recordWritten();
    }

    @Test
    void writerFailureIsRecordedAsFailedNotPropagated() {
        doThrow(new RuntimeException("boom")).when(writer).persistShared(any(), any());
        AuditLogger logger = new AuditLogger(writer, Runnable::run, metrics, AuditLogProperties.DeliveryMode.SYNC);

        logger.log(audit, clientData); // must not throw despite the writer failing

        verify(metrics, times(1)).recordFailed();
    }

    @Test
    void executorRejectionIsRecordedAsRejectedNotPropagated() {
        AuditLogger logger = new AuditLogger(writer, command -> {
            throw new RejectedExecutionException("queue full");
        }, metrics, AuditLogProperties.DeliveryMode.ASYNC);

        logger.log(audit, clientData); // must not throw

        verify(metrics).recordRejected();
        verifyNoInteractions(writer);
    }

    private Audit fixtureAudit() {
        try {
            Method method = Fixture.class.getDeclaredMethod("action");
            return method.getAnnotation(Audit.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Fixture {
        @Audit(auditType = "test", templates = {"greeting"})
        void action() {
        }
    }
}
