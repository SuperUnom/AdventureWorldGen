package io.github.luoyan.adventureworldgen.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntFunction;

/** Execution policy only: stable input/output order, bounded in-flight scratch, no semantic budget. */
public final class PlanningExecution implements AutoCloseable {
    public static final PlanningExecution SERIAL = new PlanningExecution(1, Long.MAX_VALUE);
    private final int workers;
    private final long memoryBytes;
    private final ExecutorService executor;
    private final Thread coordinator=Thread.currentThread();
    private volatile boolean cancelled;

    public PlanningExecution(int workers, long memoryBytes) {
        if (workers < 1 || memoryBytes < 1) throw new IllegalArgumentException("invalid execution limits");
        this.workers = workers;
        this.memoryBytes = memoryBytes;
        executor = workers == 1 ? null : Executors.newFixedThreadPool(workers, task -> {
            var thread = new Thread(task, "adventureworldgen-tile");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static PlanningExecution defaults(long memoryBytes) {
        int requested = Integer.getInteger("adventureworldgen.planningThreads",
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        return new PlanningExecution(Math.max(1, Math.min(requested,
                (int)Math.max(1, Math.min(256, memoryBytes / (8L << 20))))), memoryBytes);
    }

    public int workers() { return workers; }
    public static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("planning cancelled");
    }

    /** Results and exception propagation follow logical task order, never completion order. */
    public <T> List<T> map(int count, long scratchPerTask, IntFunction<T> operation) {
        if (count < 0 || scratchPerTask < 1) throw new IllegalArgumentException("invalid work estimate");
        if (scratchPerTask > memoryBytes) throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT,
                FailureStage.PLACEMENT_INDEX, "one planning task exceeds working memory");
        int parallel = (int)Math.min(workers, Math.max(1, memoryBytes / scratchPerTask));
        var result = new ArrayList<T>(count);
        for (int start = 0; start < count; start += parallel) {
            if(cancelled)throw new CancellationException("planning cancelled");
            checkCancelled();
            if (executor == null || parallel == 1) { result.add(operation.apply(start)); continue; }
            var pending = new ArrayList<Future<T>>();
            for (int i = start; i < Math.min(count, start + parallel); i++) {
                final int index = i;
                pending.add(executor.submit(() -> operation.apply(index)));
            }
            try {
                for (var future : pending) result.add(future.get());
            } catch (InterruptedException interrupted) {
                pending.forEach(f -> f.cancel(true));
                Thread.currentThread().interrupt();
                throw new CancellationException("planning interrupted");
            } catch (ExecutionException failure) {
                pending.forEach(f -> f.cancel(true));
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failure.getCause() instanceof Error error) throw error;
                throw new IllegalStateException(failure.getCause());
            }
        }
        return List.copyOf(result);
    }

    public void cancel() {
        cancelled=true;
        if(executor!=null)executor.shutdownNow();
        coordinator.interrupt();
    }

    @Override public void close() { if (executor != null) executor.shutdownNow(); }
}
