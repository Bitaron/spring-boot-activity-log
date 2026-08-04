package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.contract.AuditMetricsRecorder;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated executor audit writes are dispatched to.
 * <p>
 * Two things a plain {@code ThreadPoolTaskExecutor} bean doesn't give us out of the box:
 * <ol>
 *   <li><b>Precise shutdown accounting.</b> By default, a {@code ThreadPoolTaskExecutor} whose
 *   {@code waitForTasksToCompleteOnShutdown} is left {@code false} (the default) calls
 *   {@code shutdownNow()} immediately on context close, silently discarding whatever is still
 *   queued - on every graceful application shutdown, not just under unusual load. This class
 *   instead shuts down gracefully, waits up to the configured grace period, and only then forces
 *   a stop - reporting exactly how many writes never started via {@link AuditMetricsRecorder},
 *   using the count {@link java.util.concurrent.ExecutorService#shutdownNow()} itself returns.</li>
 *   <li><b>MDC propagation.</b> Without it, log lines emitted from the write path (a failed
 *   template, a database error) lose whatever correlation/trace id the audited request had.</li>
 * </ol>
 */
@Slf4j
public class AuditLogTaskExecutor implements Executor, DisposableBean {

    private final ThreadPoolExecutor delegate;
    private final int awaitTerminationSeconds;
    private final AuditMetricsRecorder metrics;

    public AuditLogTaskExecutor(int corePoolSize, int maxPoolSize, int queueCapacity,
                                 int awaitTerminationSeconds, AuditMetricsRecorder metrics) {
        this.awaitTerminationSeconds = awaitTerminationSeconds;
        this.metrics = metrics;
        this.delegate = new ThreadPoolExecutor(
                corePoolSize, maxPoolSize, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new AuditLogThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Submits a task, propagating the calling thread's MDC context onto whichever pool thread
     * runs it. Throws {@link RejectedExecutionException} synchronously when the queue is full -
     * callers are expected to catch it and record {@link AuditMetricsRecorder#recordRejected()}.
     */
    @Override
    public void execute(Runnable command) {
        delegate.execute(mdcPropagating(command));
    }

    private Runnable mdcPropagating(Runnable command) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (callerContext != null) {
                MDC.setContextMap(callerContext);
            }
            try {
                command.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    @Override
    public void destroy() throws InterruptedException {
        delegate.shutdown();
        boolean terminated = delegate.awaitTermination(awaitTerminationSeconds, TimeUnit.SECONDS);
        if (terminated) {
            return;
        }
        List<Runnable> neverStarted = delegate.shutdownNow();
        if (!neverStarted.isEmpty()) {
            log.warn("{} audit log write(s) were still queued after waiting {}s for shutdown and will not be persisted",
                    neverStarted.size(), awaitTerminationSeconds);
            metrics.recordDroppedOnShutdown(neverStarted.size());
        }
    }

    private static final class AuditLogThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "audit-log-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
