package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BoardwalkPlannerTest {
    private static final MacroTerrain CLIFF=(x,z)->new MacroSample(x>=8?124:64,Double.NaN,WaterKind.NONE,false,"r","mountains","test");
    private RoadSettings settings() {return RoadConfigJson.read(com.google.gson.JsonParser.parseString("{\"enabled\":true,\"maximum_grade\":1}"));}
    @Test void cliffNetworkIsIdenticalWithOneTwoAndFourWorkers() {
        var config=new AdventureWorldConfigParser().parse("""
            {"world":{"radius":256},"spawn":{"biome":"test:plain"},"biomes":{"filler":["test:plain"],
             "required":[{"id":"test:end","adventure_level":1,"road":{"enabled":true,"required":true}}]},
             "roads":{"enabled":true,"maximum_grade":1,"loop_budget_fraction":0}}
            """);
        var patch=new PlannedBiomePatch("end",new ContentId("test:end"),1,12,80,16,84);
        RoadPlan expected=null;
        for(int workers:new int[]{1,2,4})try(var execution=new PlanningExecution(workers,512L<<20)) {
            var road=new RoadPlanner(7331,config,CLIFF,RoadWorkControl.AUTOMATIC,execution).plan(
                    new AdventurePlanView.SpawnPosition(.5,65,.5,0),List.of(patch),List.of(),
                    StructurePlanningCatalog.fromIds(List.of()),(x,z)->patch.contains(x,z)?patch.biomeId():new ContentId("test:plain"));
            assertTrue(road.columns().stream().anyMatch(c->c.kind()==RoadPlan.Kind.BOARDWALK));
            if(expected==null)expected=road;else assertEquals(expected,road);
        }
    }
    @Test void aDetachedCliffRampHasSupportAndLeavesTheValleyOpen() {
        var result=new BoardwalkPlanner(settings(),CLIFF,List.of(),512).build(List.of(
                new RoadPlan.Point3(.5,63,.5),new RoadPlan.Point3(.5,123,120.5),new RoadPlan.Point3(12.5,123,120.5)),200);
        assertNotNull(result);
        assertTrue(result.supports().stream().anyMatch(s->s.kind()==RoadPlan.SupportKind.BEAM));
        assertTrue(result.columns().stream().anyMatch(c->c.deckY()-CLIFF.sample(c.x()+.5,c.z()+.5).groundSurface()>30));
        assertTrue(result.columns().stream().allMatch(c->c.bottomY()==c.deckY()-1));
        var points=result.points().stream().map(p->new Vec2(p.x(),p.z())).toList();
        assertDoesNotThrow(()->new RoadPlan(List.of(new RoadPlan.Node("spawn",0,0,true,63,RoadPlan.NodeKind.DESTINATION),
                new RoadPlan.Node("end",12,120,true,123,RoadPlan.NodeKind.DESTINATION)),
                List.of(new RoadPlan.Route("r","spawn","end",points,132,result.points(),RoadPlan.Kind.BOARDWALK)),
                result.columns(),List.of(),List.of(),0,result.supports()));
    }
    @Test void searchFindsAHeightIndependentRouteBesideTheCliff() {
        var planner=new BoardwalkPlanner(settings(),CLIFF,List.of(),256);
        var result=planner.find(new Vec2(.5,.5),new Vec2(12.5,80.5));
        assertNotNull(result,"a cliff-side ascent should be found");
        for(int i=1;i<result.points().size();i++) {
            var a=result.points().get(i-1);var b=result.points().get(i);
            assertTrue(Math.abs(b.y()-a.y())<=Math.hypot(b.x()-a.x(),b.z()-a.z())+1e-8);
        }
    }
    @Test void aSteepRoundMountainCanBeClimbedByWindingAlongItsSide() {
        MacroTerrain mountain=(x,z)->new MacroSample(Math.hypot(x,z)<=30?140:64,Double.NaN,
                WaterKind.NONE,false,"r","mountains","test");
        var planner=new BoardwalkPlanner(settings(),mountain,List.of(),256);
        var result=planner.findWithConnectors(RoadAccessCandidates.Access.point(new Vec2(-35.5,.5)),
                RoadAccessCandidates.Access.point(new Vec2(.5,.5)),63,139,true);
        assertNotNull(result,"a supported circular ascent should avoid the vertical mountain wall");
        assertTrue(result.points().stream().anyMatch(p->Math.abs(p.z())>25));
        assertTrue(result.supports().stream().anyMatch(s->s.kind()==RoadPlan.SupportKind.BEAM));
        for(int i=1;i<result.points().size();i++) {
            var a=result.points().get(i-1);var b=result.points().get(i);
            assertTrue(Math.abs(b.y()-a.y())<=Math.hypot(b.x()-a.x(),b.z()-a.z())+1e-8);
        }
    }
    @Test void anExplicitGentlerGradeRequiresALongerClimb() {
        var gentle=RoadConfigJson.read(com.google.gson.JsonParser.parseString("{\"enabled\":true,\"maximum_grade\":0.35}"));
        var planner=new BoardwalkPlanner(gentle,CLIFF,List.of(),512);
        assertNull(planner.build(List.of(new RoadPlan.Point3(.5,63,.5),new RoadPlan.Point3(.5,123,120.5)),120));
        var result=planner.find(new Vec2(.5,.5),new Vec2(12.5,80.5));assertNotNull(result);
        for(int i=1;i<result.points().size();i++) {
            var a=result.points().get(i-1);var b=result.points().get(i);
            assertTrue(Math.abs(b.y()-a.y())<=Math.hypot(b.x()-a.x(),b.z()-a.z())*.35+1e-8);
        }
    }
    @Test void floatingDeckAndExcessiveGradeAreRejected() {
        MacroTerrain flat=(x,z)->new MacroSample(64,Double.NaN,WaterKind.NONE,false,"r","plains","test");
        assertNull(new BoardwalkPlanner(settings(),flat,List.of(),256).build(List.of(new RoadPlan.Point3(0,100,0),new RoadPlan.Point3(40,100,0)),40));
        assertNull(new BoardwalkPlanner(settings(),CLIFF,List.of(),256).build(List.of(new RoadPlan.Point3(0,63,0),new RoadPlan.Point3(0,123,20)),20));
    }
}
