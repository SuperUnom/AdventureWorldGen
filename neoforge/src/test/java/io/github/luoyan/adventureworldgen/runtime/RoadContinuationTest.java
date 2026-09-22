package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.plan.RoadWorkControl;
import io.github.luoyan.adventureworldgen.plan.PlanningStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class RoadContinuationTest {
    @TempDir Path directory;
    private static void awaitPaused() throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(PlanningProgress.current().status()!=PlanningProgress.Status.PAUSED) {
            if(System.nanoTime()>end)fail("planner did not suspend");Thread.sleep(5);
        }
    }
    @Test void continuationKeepsTheWorkerPendingAndResumesExactlyOnce() throws Exception {
        var run=PlanningProgress.begin("test:resume");run.stage(PlanningStage.ROADS);
        try(var executor=Executors.newSingleThreadExecutor()) {
            try {
                var future=executor.submit(()->{run.manualRoadWork(directory).pause(new RoadWorkControl.Pause("a",RoadWorkControl.Kind.OPERATIONS,10,10));return 42;});
                awaitPaused();assertFalse(future.isDone());assertFalse(run.snapshot().status()==PlanningProgress.Status.FAILED);
                assertTrue(PlanningProgress.continueRoadWork());assertFalse(PlanningProgress.continueRoadWork());
                assertEquals(42,future.get(5,TimeUnit.SECONDS));assertEquals(PlanningProgress.Status.RUNNING,run.snapshot().status());
            } finally {PlanningProgress.clear();}
        }
    }
    @Test void hardCapacityCannotResumeAndClosingTheRunReleasesItsWorker() throws Exception {
        var run=PlanningProgress.begin("test:capacity");run.stage(PlanningStage.ROADS);
        try(var executor=Executors.newSingleThreadExecutor()) {
            try {
                var future=executor.submit(()->run.manualRoadWork(directory).pause(new RoadWorkControl.Pause("a",RoadWorkControl.Kind.COLUMNS,10,10)));
                awaitPaused();assertFalse(PlanningProgress.continueRoadWork());assertFalse(future.isDone());
                PlanningProgress.clear();
                var failure=assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class,failure.getCause());
                assertEquals(PlanningProgress.Status.CANCELLED,run.snapshot().status());
            } finally {PlanningProgress.clear();}
        }
    }
    @Test void dedicatedServerContinuationConsumesOnlyTheCurrentToken() throws Exception {
        var run=PlanningProgress.begin("test:headless");run.stage(PlanningStage.ROADS);
        try(var executor=Executors.newSingleThreadExecutor()) {
            try {
                var future=executor.submit(()->run.manualRoadWork(directory).pause(new RoadWorkControl.Pause("a",RoadWorkControl.Kind.OPERATIONS,10,10)));
                awaitPaused();var status=directory.resolve("adventureworldgen/road-planning-status.txt");
                assertTimeoutPreemptively(Duration.ofSeconds(5),()->{while(!Files.exists(status))Thread.sleep(5);});
                String text=Files.readString(status);String token=text.substring(text.indexOf("'resume ")+8,text.indexOf("' to "));
                Files.writeString(directory.resolve("adventureworldgen/road-planning-command.txt"),"resume "+token);
                future.get(5,TimeUnit.SECONDS);assertFalse(Files.exists(status));
            } finally {PlanningProgress.clear();}
        }
    }
}
