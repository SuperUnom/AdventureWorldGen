package io.github.luoyan.adventureworldgen.runtime;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;

/** Observation only: progress never participates in generation, retries or random inputs. */
public final class PlanningProgress {
    public enum Stage {
        CACHE(0, 2), COAST(2, 8), EROSION(8, 46), RIVERS(46, 55), COSTS(55, 75), PLACEMENT(75, 78), TEMPERATURE(78, 80), HUMIDITY(80, 81), SEEDS(81, 84), GROWTH(84, 89), STRUCTURES(89, 90), FILLER(90, 94), TRANSITION(94, 95), VALIDATION(95, 98), SAVE(98, 100);
        final int start, end;
        Stage(int start, int end) { this.start = start; this.end = end; }
        public String translationKey() { return "adventureworldgen.planning." + name().toLowerCase(java.util.Locale.ROOT); }
    }
    public enum Status { RUNNING, READY, FAILED }
    public record Snapshot(String profile, Stage stage, int percent, Status status, long startedNanos, String detail) {
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
    public static void stageCurrent(Stage stage) { var run=CURRENT.get();if(run!=null)run.stage(stage); }
    public static DoubleConsumer withinCurrent(Stage stage) { return value->{var run=CURRENT.get();if(run!=null)run.update(stage,value);}; }
    public static void detailCurrent(String detail) {var run=CURRENT.get();if(run!=null)run.detail(detail);}
    public static void clear() { CURRENT.set(null); }

    public static final class Run {
        private volatile Snapshot snapshot;
        private Run(String profile) { snapshot = new Snapshot(profile, Stage.CACHE, 0, Status.RUNNING, System.nanoTime(), ""); }
        public Snapshot snapshot() { return snapshot; }
        public void stage(Stage stage) { update(stage, 0); }
        public DoubleConsumer within(Stage stage) { return fraction -> update(stage, fraction); }
        private synchronized void update(Stage stage, double fraction) {
            Snapshot before = snapshot;
            if (before.status != Status.RUNNING || stage.ordinal() < before.stage.ordinal()) return;
            if (!Double.isFinite(fraction)) return;
            int percent = (int) Math.floor(stage.start + (stage.end - stage.start) * Math.clamp(fraction, 0, 1));
            snapshot = new Snapshot(before.profile, stage, Math.max(before.percent, Math.min(99, percent)),
                    Status.RUNNING, before.startedNanos, stage==before.stage?before.detail:"");
        }
        public synchronized void detail(String text) {
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
