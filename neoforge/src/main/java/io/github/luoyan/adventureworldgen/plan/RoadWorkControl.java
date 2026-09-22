package io.github.luoyan.adventureworldgen.plan;

/** Scheduling only. A continuation must preserve the caller's stack and never alter geometry. */
@FunctionalInterface
public interface RoadWorkControl {
    enum Kind { OPERATIONS, SEARCH_EXPANSIONS, NODES, COLUMNS, SEARCH_STATES }
    record Pause(String task, Kind kind, long used, long limit) {
        public boolean resumable() { return kind==Kind.OPERATIONS||kind==Kind.SEARCH_EXPANSIONS; }
    }
    /** Blocks at a checkpoint. Returning grants one more equal-sized computation batch only. */
    void pause(Pause pause);
    default void checkCancelled() { PlanningExecution.checkCancelled(); }

    /** Default continuation automatically renews work; storage capacity remains a hard limit. */
    RoadWorkControl AUTOMATIC = pause -> {
        io.github.luoyan.adventureworldgen.plan.PlanningExecution.checkCancelled();
        if(!pause.resumable())throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT,FailureStage.ROADS,
                "road hard capacity exhausted",java.util.Map.of("kind",pause.kind(),"used",pause.used(),"limit",pause.limit()));
    };
    RoadWorkControl REPORT = pause -> { throw new Suspended(pause); };
    final class Suspended extends RuntimeException {
        private final Pause pause;
        public Suspended(Pause pause) { super("road planning paused: "+pause);this.pause=pause; }
        public Pause pause() {return pause;}
    }
}
