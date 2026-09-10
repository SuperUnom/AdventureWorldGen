package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.plan.PlanningObserver;
import io.github.luoyan.adventureworldgen.plan.PlanningStage;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;

/** Observation only: progress never participates in generation, retries or random inputs. */
public final class PlanningProgress {
    public enum Status { RUNNING, READY, FAILED }
    public record Snapshot(String profile, PlanningStage stage, int percent, Status status, long startedNanos, String detail) {
        public long elapsedSeconds() { return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000_000); }
    }
    private static final AtomicReference<Run> CURRENT = new AtomicReference<>();
    private PlanningProgress() {}
    public static Run begin(String profile) {
        Run run = new Run(profile);
        CURRENT.set(run);
        return run;
    }
    public static Snapshot current() { Run run = CURRENT.get(); return run == null ? null : run.snapshot(); }
    public static void clear() { CURRENT.set(null); }

    /**
     * The single active run. It implements {@link PlanningObserver} so the algorithms receive
     * progress as an explicit parameter instead of reaching for this static registry.
     */
    public static final class Run implements PlanningObserver {
        private volatile Snapshot snapshot;
        private Run(String profile) { snapshot = new Snapshot(profile, PlanningStage.CACHE, 0, Status.RUNNING, System.nanoTime(), ""); }
        public Snapshot snapshot() { return snapshot; }
        @Override public void stage(PlanningStage stage) { update(stage, 0); }
        @Override public DoubleConsumer within(PlanningStage stage) { return fraction -> update(stage, fraction); }
        private synchronized void update(PlanningStage stage, double fraction) {
            Snapshot before = snapshot;
            if (before.status != Status.RUNNING || stage.ordinal() < before.stage.ordinal()) return;
            if (!Double.isFinite(fraction)) return;
            int percent = (int) Math.floor(stage.start() + (stage.end() - stage.start()) * Math.clamp(fraction, 0, 1));
            snapshot = new Snapshot(before.profile, stage, Math.max(before.percent, Math.min(99, percent)),
                    Status.RUNNING, before.startedNanos, stage==before.stage?before.detail:"");
        }
        @Override public synchronized void detail(String text) {
            snapshot=new Snapshot(snapshot.profile,snapshot.stage,snapshot.percent,snapshot.status,snapshot.startedNanos,text);
        }
        public synchronized void complete() {
            if (snapshot.status != Status.RUNNING) return;
            snapshot = new Snapshot(snapshot.profile, snapshot.stage, 100, Status.READY, snapshot.startedNanos, snapshot.detail);
        }
        public synchronized void fail() {
            if (snapshot.status != Status.RUNNING) return;
            snapshot = new Snapshot(snapshot.profile, snapshot.stage, snapshot.percent, Status.FAILED, snapshot.startedNanos, snapshot.detail);
        }
    }
}
