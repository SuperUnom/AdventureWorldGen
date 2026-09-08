package io.github.luoyan.adventureworldgen.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanningProgressTest {
    @Test void progressIsMonotoneAndPublicationAloneCompletesIt() {
        var run = PlanningProgress.begin("test");
        var erosion = run.within(PlanningProgress.Stage.EROSION);
        erosion.accept(0.7); int previous = run.snapshot().percent();
        erosion.accept(0.2); assertEquals(previous, run.snapshot().percent());
        run.stage(PlanningProgress.Stage.COSTS); erosion.accept(1);
        assertEquals(PlanningProgress.Stage.COSTS, run.snapshot().stage());
        run.within(PlanningProgress.Stage.SAVE).accept(1);
        assertEquals(99, run.snapshot().percent());
        assertEquals(PlanningProgress.Status.RUNNING, run.snapshot().status());
        run.complete(); assertEquals(100, run.snapshot().percent());
        assertEquals(PlanningProgress.Status.READY, run.snapshot().status());
        PlanningProgress.clear();
    }
    @Test void failedOrOldRunsCannotOverwriteANewWorldsProgress() {
        var old = PlanningProgress.begin("old");
        old.stage(PlanningProgress.Stage.COAST); old.fail(); old.complete();
        assertEquals(PlanningProgress.Status.FAILED, old.snapshot().status());
        var next = PlanningProgress.begin("next");
        old.within(PlanningProgress.Stage.SAVE).accept(1);
        assertEquals(next.snapshot(), PlanningProgress.current());
        PlanningProgress.clear(); assertNull(PlanningProgress.current());
    }
}
