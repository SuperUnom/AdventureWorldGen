package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.plan.PlanningExecution;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanningExecutionTest {
    @Test void completionOrderAndWorkerCountDoNotChangeResults() {
        List<Integer> expected=java.util.stream.IntStream.range(0,31).map(i->i*i).boxed().toList();
        for(int workers:new int[]{1,2,4})try(var execution=new PlanningExecution(workers,1024)) {
            assertEquals(expected,execution.map(31,256,i->{Thread.yield();return i*i;}));
        }
    }
    @Test void atlasIsExactAcrossNegativeTilesAndEviction() {
        MacroTerrain terrain=(x,z)->new MacroSample(80+Math.sin(x*.02)+Math.cos(z*.03),Double.NaN,WaterKind.NONE,false,"r","plains","test");
        for(int workers:new int[]{1,4})try(var execution=new PlanningExecution(workers,8L<<20)) {
            var atlas=new PlanningAtlas(terrain,1);atlas.prepare(256,execution);
            for(double x:new double[]{-258,-254,-2,2,254,258,2.25})for(double z:new double[]{-258,-2,2,258,6.5})
                assertEquals(terrain.sample(x,z),atlas.sample(x,z));
        }
    }
    @Test void completeJointLayoutIsIndependentOfWorkerCount() {
        var config=new io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser().parse("""
            {"world":{"radius":256},"spawn":{"biome":"test:plain"},"biomes":{"filler":["test:plain"],
             "required":[{"id":"test:other","adventure_level":4,"area":{"min":1024,"max":4096}}]}}
            """);
        MacroTerrain terrain=(x,z)->new MacroSample(80,Double.NaN,WaterKind.NONE,false,"r","plains","test");
        io.github.luoyan.adventureworldgen.planner.JointPlanner.Result expected=null;
        for(int workers:new int[]{1,2,4})try(var execution=new PlanningExecution(workers,64L<<20)) {
            var atlas=new PlanningAtlas(terrain,16L<<20);atlas.prepare(256,execution);
            var result=new io.github.luoyan.adventureworldgen.planner.JointPlanner(io.github.luoyan.adventureworldgen.plan.PlannerProfile.V2,execution)
                    .plan(7331,config,atlas,io.github.luoyan.adventureworldgen.plan.StructurePlanningCatalog.fromIds(List.of()));
            if(expected==null)expected=result;else assertEquals(expected,result);
        }
    }
}
