package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.plan.PlanningObserver;
import io.github.luoyan.adventureworldgen.plan.PlanningStage;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;

/** Progress observation and an explicit, separate user continuation gate for road work. */
public final class PlanningProgress {
    public enum Status { RUNNING, PAUSED, READY, FAILED, CANCELLED }
    public record Snapshot(String profile, PlanningStage stage, int percent, Status status, long startedNanos, String detail) {
        public long elapsedSeconds() { return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000_000); }
    }
    private static final AtomicReference<Run> CURRENT = new AtomicReference<>();
    private static final java.util.Set<Run> ACTIVE=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private PlanningProgress() {}
    public static Run begin(String profile) {
        Run run = new Run(profile);
        ACTIVE.add(run);CURRENT.set(run);
        return run;
    }
    public static Snapshot current() { Run run = CURRENT.get(); return run == null ? null : run.snapshot(); }
    public static void clear() {CURRENT.set(null);for(var run:java.util.List.copyOf(ACTIVE))run.cancel();}
    public static void cancelCurrent() {Run run=CURRENT.get();if(run!=null)run.cancel();}
    public static io.github.luoyan.adventureworldgen.plan.RoadWorkControl.Pause roadPause() {
        Run run=CURRENT.get();return run==null?null:run.roadPause;
    }
    public static boolean continueRoadWork() {Run run=CURRENT.get();return run!=null&&run.resumeRoadWork();}

    /**
     * The single active run. It implements {@link PlanningObserver} so the algorithms receive
     * progress as an explicit parameter instead of reaching for this static registry.
     */
    public static final class Run implements PlanningObserver {
        private volatile Snapshot snapshot;
        private volatile io.github.luoyan.adventureworldgen.plan.RoadWorkControl.Pause roadPause;
        private volatile boolean cancelled;
        private final String runId=java.util.UUID.randomUUID().toString();
        private int pauseNumber;
        private Runnable cancellation=()->{};
        public synchronized void onCancel(Runnable action){cancellation=action;if(cancelled)action.run();}

        /** Control is separate from the observation-only PlanningObserver contract. */
        public io.github.luoyan.adventureworldgen.plan.RoadWorkControl roadWork(java.nio.file.Path world) {
            return new io.github.luoyan.adventureworldgen.plan.RoadWorkControl() {
                public void pause(Pause request) {
                    checkCancelled();
                    if(request.resumable())detail("道路继续计算："+request.task()+" / "+request.used());
                    else io.github.luoyan.adventureworldgen.plan.RoadWorkControl.AUTOMATIC.pause(request);
                }
                public void checkCancelled() {
                    if(cancelled||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("planning cancelled");
                }
            };
        }
        /** Explicit manual control remains available to diagnostic clients; production renews batches automatically. */
        public io.github.luoyan.adventureworldgen.plan.RoadWorkControl manualRoadWork(java.nio.file.Path world) {
            return request->awaitRoadWork(request,world);
        }
        private synchronized void awaitRoadWork(io.github.luoyan.adventureworldgen.plan.RoadWorkControl.Pause request,
                                                java.nio.file.Path world) {
            if(cancelled)throw new java.util.concurrent.CancellationException("planning cancelled");
            String token=runId+"/"+(++pauseNumber);
            var status=world.resolve("adventureworldgen/road-planning-status.txt");
            var command=world.resolve("adventureworldgen/road-planning-command.txt");
            var before=snapshot;
            roadPause=request;
            snapshot=new Snapshot(before.profile,before.stage,before.percent,Status.PAUSED,before.startedNanos,before.detail);
            String message="Road planning paused: "+request+". "+(request.resumable()
                    ?"Continue in the loading screen, or write 'resume "+token+"' to "+command
                    :"Hard capacity retained; continuation cannot increase it. Reduce the configured workload or revise capacity before a new run.");
            System.getLogger(PlanningProgress.class.getName()).log(System.Logger.Level.WARNING,message);
            try {
                java.nio.file.Files.createDirectories(status.getParent());
                java.nio.file.Files.writeString(status,message+"\nCancel: cancel "+token+"\n");
                while(roadPause!=null&&!cancelled) {
                    wait(500);
                    if(java.nio.file.Files.exists(command)) {
                        String action=java.nio.file.Files.readString(command).strip();
                        if(action.equals("resume "+token)&&request.resumable()) {
                            java.nio.file.Files.delete(command);resumeRoadWork();
                        } else if(action.equals("cancel "+token)) {
                            java.nio.file.Files.delete(command);cancel();
                        }
                    }
                }
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();cancel();
            } catch(java.io.IOException unavailable) {
                System.getLogger(PlanningProgress.class.getName()).log(System.Logger.Level.WARNING,"Road continuation file unavailable; use the loading screen",unavailable);
                while(roadPause!=null&&!cancelled)try {wait();}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();cancel();}
            } finally {
                try {java.nio.file.Files.deleteIfExists(status);}catch(java.io.IOException ignored) {}
            }
            if(cancelled)throw new java.util.concurrent.CancellationException("planning cancelled");
            snapshot=new Snapshot(before.profile,before.stage,before.percent,Status.RUNNING,before.startedNanos,before.detail);
        }
        public synchronized boolean resumeRoadWork() {
            if(cancelled||roadPause==null||!roadPause.resumable())return false;
            roadPause=null;notifyAll();return true;
        }
        public synchronized void cancel() {
            if(snapshot.status==Status.READY||snapshot.status==Status.FAILED||snapshot.status==Status.CANCELLED)return;
            cancelled=true;roadPause=null;ACTIVE.remove(this);
            cancellation.run();
            snapshot=new Snapshot(snapshot.profile,snapshot.stage,snapshot.percent,Status.CANCELLED,snapshot.startedNanos,snapshot.detail);
            notifyAll();
        }
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
            ACTIVE.remove(this);
        }
        public synchronized void fail() {
            if (snapshot.status != Status.RUNNING) return;
            snapshot = new Snapshot(snapshot.profile, snapshot.stage, snapshot.percent, Status.FAILED, snapshot.startedNanos, snapshot.detail);
            ACTIVE.remove(this);
        }
    }
}
