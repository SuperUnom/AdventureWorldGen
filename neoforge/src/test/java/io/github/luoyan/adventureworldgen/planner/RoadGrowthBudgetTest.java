package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.config.RoadConfigJson;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadGrowthBudgetTest {
    private PlanningPolicy.RoadPolicy policy(long samples,long visits) {
        return new PlanningPolicy.RoadPolicy(4,12,4,16,8,2,128,32,1000,2000,samples,visits,256,32,2.0);
    }
    @Test void renewedBatchesNestedPhasesAndRetryCannotResetTheDestinationLimit() {
        var work=new RoadWorkBudget(1,RoadWorkControl.AUTOMATIC,policy(3,10));
        work.task("a");work.operation();
        try(var phase=work.phase("construction")){work.operation();}
        work.task("b");work.operation();work.task("a");work.operation();
        assertThrows(RoadWorkBudget.Limit.class,work::operation);
        assertEquals(3,work.samples());work.task("b");assertDoesNotThrow(work::operation);
    }
    @Test void candidateAndSearchCapsIncludeEveryAlternativeAndQueuePop() {
        var work=new RoadWorkBudget(100,RoadWorkControl.AUTOMATIC,policy(100,100));
        for(int i=0;i<12;i++)assertTrue(work.candidate(10));
        work.task("endpoints");assertFalse(work.candidate(10));
        for(int i=0;i<4;i++)assertTrue(work.validation());assertFalse(work.validation());
        for(int i=0;i<16;i++)assertTrue(work.expansion(false));assertFalse(work.expansion(false));
        for(int i=0;i<8;i++)assertTrue(work.expansion(true));assertFalse(work.expansion(true));
        assertTrue(work.repair());assertTrue(work.repair());assertFalse(work.repair());
    }
    @Test void longCandidatesAndGeometryVisitsHaveIndependentHardCaps() {
        var work=new RoadWorkBudget(100,RoadWorkControl.AUTOMATIC,policy(100,2));
        assertFalse(work.candidate(1001));assertEquals(0,work.candidates());
        assertTrue(work.candidate(1000));assertTrue(work.candidate(1000));assertFalse(work.candidate(1));
        work.visit();work.visit();assertThrows(RoadWorkBudget.Limit.class,work::visit);
    }
    @Test void middleAttachmentUsesSegmentsBetweenControlPointsIncludingNegativeTiles() {
        var work=new RoadWorkBudget(100000,RoadWorkControl.AUTOMATIC);
        var network=new RoadNetwork();network.route(List.of(new Vec2(-250.5,-64.5),new Vec2(-10.5,-64.5)),0,work);
        assertFalse(network.nearby(new Vec2(-128.5,-30.5),work).isEmpty());
    }
    @Test void branchValidationDoesNotSampleOrMoveDistantOldRoads() {
        var settings=RoadConfigJson.read(com.google.gson.JsonParser.parseString("{\"enabled\":true}"));
        var old=new HashMap<Long,RoadPlan.Column>();
        for(int x=-1000;x<=1000;x++)for(int z=-1;z<=1;z++)old.put(RoadPlan.key(x,z),new RoadPlan.Column(x,z,63,62,66,false));
        var result=RoadConstruction.build(List.of(new Vec2(.5,.5),new Vec2(.5,80.5)),settings,(x,z)-> {
            assertTrue(Math.abs(x)<40,"unrelated old road was sampled");
            return new MacroSample(64,Double.NaN,WaterKind.NONE,false,"r","plains","test");
        },List.of(),old,0,0,63);
        assertTrue(result.valid(),result.failure());
        assertTrue(result.columns().stream().allMatch(c->Math.abs(c.x())<40));
        assertTrue(old.values().stream().allMatch(c->c.deckY()==63));
    }
    @Test void branchReusesTheLastContactAcrossDifferentOldRoadsInsteadOfClosingATriangle() {
        var work=new RoadWorkBudget(100000,RoadWorkControl.AUTOMATIC);var network=new RoadNetwork();
        var columns=new ArrayList<RoadPlan.Column>();
        for(int x=-40;x<=40;x++)columns.add(new RoadPlan.Column(x,0,63,62,66,false));
        for(int z=0;z<=40;z++)columns.add(new RoadPlan.Column(0,z,63,62,66,false));
        network.commit(columns,List.of());
        var path=List.of(new Vec2(-30.5,.5),new Vec2(.5,30.5),new Vec2(40.5,50.5));
        var rejoin=network.lastGroundContact(path,path.size()-1,2,work);
        assertNotNull(rejoin);assertEquals(0,rejoin.column().x());
        assertTrue(rejoin.column().z()>=30);assertEquals(path.getLast(),rejoin.path().getLast());
        assertTrue(RoadShape.length(rejoin.path())<RoadShape.length(path)-35);
        assertTrue(network.hasUnhelpfulExcursion(path,work),"a long shortcut must not hide a tiny intermediate triangle");
        var bridge=new RoadNetwork();bridge.commit(List.of(new RoadPlan.Column(0,30,63,62,66,true)),List.of());
        assertNull(bridge.lastGroundContact(path,path.size()-1,2,work),"bridge/elevated crossing must not become a ground junction");
    }
    @Test void returningCloseToTheOriginalJunctionStillRemovesTheRedundantPrefix() {
        var work=new RoadWorkBudget(10000,RoadWorkControl.AUTOMATIC);var network=new RoadNetwork();
        network.commit(List.of(new RoadPlan.Column(0,0,63,62,66,false)),List.of());
        var path=List.of(new Vec2(.5,.5),new Vec2(4.5,4.5),new Vec2(.5,.5),new Vec2(40.5,.5));
        var result=network.lastGroundContact(path,path.size()-1,2,work);
        assertNotNull(result);assertEquals(40,RoadShape.length(result.path()),1e-7);
    }
    @Test void tinyLoopsPayAnAbsolutePenaltyWhileUsefulLargeShortcutsRemainAvailable() {
        assertFalse(RoadPlanner.worthwhileLoop(50,20));
        assertFalse(RoadPlanner.worthwhileLoop(80,50));
        assertTrue(RoadPlanner.worthwhileLoop(200,100));
        assertFalse(RoadPlanner.worthwhileLoop(Double.POSITIVE_INFINITY,100));
    }
    @Test void anotherDeckAtTheSameXZCannotSupplyConnectivityAndSupportsCannotBlockHeadroom() {
        var work=new RoadWorkBudget(10000,RoadWorkControl.AUTOMATIC);var network=new RoadNetwork();
        var low=new RoadPlan.Column(0,0,63,62,66,false);network.commit(List.of(low),List.of());
        var high=new RoadPlan.Column(0,0,80,79,83,true,false,RoadPlan.Kind.BOARDWALK,-1);
        assertEquals("DISCONNECTED_DELTA",network.validate(List.of(high),List.of(),new Vec2(.5,.5),63,100,work));
        var beam=new RoadPlan.Support(0,65,0,1,65,0,RoadPlan.SupportKind.BEAM);
        assertEquals("SUPPORT_HEADROOM",network.validate(List.of(new RoadPlan.Column(1,0,63,62,66,false)),List.of(beam),new Vec2(.5,.5),63,100,work));
    }
}
