package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.contract.AuditMetricsRecorder;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuditLogTaskExecutorTest {

    @Test
    void executesSubmittedTasks() throws InterruptedException {
        AuditMetricsRecorder metrics = mock(AuditMetricsRecorder.class);
        AuditLogTaskExecutor executor = new AuditLogTaskExecutor(1, 1, 10, 5, metrics);
        CountDownLatch ran = new CountDownLatch(1);

        executor.execute(ran::countDown);

        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
        executor.destroy();
        verify(metrics, never()).recordDroppedOnShutdown(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void propagatesMdcContextOntoTheWorkerThread() throws InterruptedException {
        AuditMetricsRecorder metrics = mock(AuditMetricsRecorder.class);
        AuditLogTaskExecutor executor = new AuditLogTaskExecutor(1, 1, 10, 5, metrics);
        AtomicReference<String> seenOnWorkerThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        MDC.put("traceId", "abc-123");
        try {
            executor.execute(() -> {
                seenOnWorkerThread.set(MDC.get("traceId"));
                done.countDown();
            });
        } finally {
            MDC.clear();
        }

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seenOnWorkerThread.get()).isEqualTo("abc-123");
        executor.destroy();
    }

    @Test
    void queueSaturationThrowsRejectedExecutionExceptionRatherThanBlockingOrSilentlyDropping() throws InterruptedException {
        AuditMetricsRecorder metrics = mock(AuditMetricsRecorder.class);
        // Single worker thread, 1-deep queue: the first task occupies the only worker, the
        // second fills the queue, the third has nowhere to go.
        AuditLogTaskExecutor executor = new AuditLogTaskExecutor(1, 1, 1, 5, metrics);
        CountDownLatch blockFirstTask = new CountDownLatch(1);
        CountDownLatch firstTaskStarted = new CountDownLatch(1);

        executor.execute(() -> {
            firstTaskStarted.countDown();
            awaitUninterruptibly(blockFirstTask);
        });
        assertThat(firstTaskStarted.await(2, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> { }); // occupies the single queue slot

        assertThatThrownBy(() -> executor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);

        blockFirstTask.countDown();
        executor.destroy();
    }

    /**
     * The direct regression test for the "dropped_on_shutdown" metric: a task still running past
     * the configured grace period is force-stopped, and the count of tasks that never got to run
     * (from {@link java.util.concurrent.ExecutorService#shutdownNow()}'s own return value, not an
     * estimate) is reported rather than silently discarded.
     */
    @Test
    void tasksStillQueuedPastTheGracePeriodAreReportedAsDroppedOnShutdown() throws InterruptedException {
        AuditMetricsRecorder metrics = mock(AuditMetricsRecorder.class);
        // Single worker thread; the first task blocks forever, everything else piles up behind it.
        AuditLogTaskExecutor executor = new AuditLogTaskExecutor(1, 1, 10, 1, metrics);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        CountDownLatch taskStarted = new CountDownLatch(1);

        executor.execute(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        assertThat(taskStarted.await(2, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> { }); // never runs - queued behind the blocked worker

        executor.destroy(); // awaitTerminationSeconds=1, well under the 30s the first task sleeps for

        verify(metrics).recordDroppedOnShutdown(1); // the queued task; the running one isn't counted
    }

    private void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
