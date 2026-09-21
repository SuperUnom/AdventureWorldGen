package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.plan.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerTest {
    record Destination(String id,int x,int z,boolean required) {}
    record Fixture(AdventureWorldConfig config,List<PlannedBiomePatch> patches) {}
    static MacroSample land(double y){return new MacroSample(y,Double.NaN,WaterKind.NONE,false,"plain","plains","test");}
    static Fixture settings(String extra,Destination... destinations) {
        var base=new AdventureWorldConfigParser().parse("{\"world\":{\"radius\":1024},\"spawn\":{\"biome\":\"test:plains\"},\"biomes\":{\"filler\":[\"test:plains\"]},\"roads\":{\"enabled\":true"+extra+"}}");
        var requests=new ArrayList<AdventureWorldConfig.RequiredBiome>();var patches=new ArrayList<PlannedBiomePatch>();
        for(var d:destinations) {
            var id=new ContentId("test:"+d.id());
            requests.add(new AdventureWorldConfig.RequiredBiome(d.id(),id,1,AdventureWorldConfig.AreaRange.DEFAULT,new AdventureWorldConfig.RoadConnection(true,d.required())));
            patches.add(new PlannedBiomePatch("patch/"+d.id(),id,1,d.x()-2,d.z()-2,d.x()+6,d.z()+6));
        }
        return new Fixture(new AdventureWorldConfig(base.world(),base.spawn(),new AdventureWorldConfig.BiomeSettings(requests,base.biomes().filler()),List.of(),base.roads()),patches);
    }
    static RoadPlan plan(Fixture f,MacroTerrain terrain) {
        return new RoadPlanner(472,f.config(),terrain).plan(new AdventurePlanView.SpawnPosition(.5,64,.5,0),f.patches(),List.of(),StructurePlanningCatalog.fromIds(List.of()),
                (x,z)->f.patches().stream().filter(p->p.contains(x,z)).map(PlannedBiomePatch::biomeId).findFirst().orElse(new ContentId("test:plains")));
    }
    @Test void plainsHaveLongGentleBendsWithBoundedDetourAndFullWidth() {
        var settings=settings("",new Destination("end",400,0,true));var road=plan(settings,(x,z)->land(64));
        assertEquals(1,road.routes().size());var route=road.routes().getFirst();
        assertTrue(route.points().stream().anyMatch(p->Math.abs(p.z()-.5)>6));assertTrue(route.length()>400&&route.length()<=440);
        var cells=new HashSet<Long>();road.columns().forEach(c->{cells.add(RoadPlan.key(c.x(),c.z()));assertEquals(63,c.deckY());});
        for(var p:route.points())assertTrue(cells.contains(RoadPlan.key((int)Math.floor(p.x()),(int)Math.floor(p.z()))));
        assertEquals(road,plan(settings,(x,z)->land(64)));
    }
    @Test void gentleHillsRemainFeasibleAtPinnedSpawnAndJunctions() {
        var settings=settings("",new Destination("end",480,0,true));var road=plan(settings,(x,z)->land(64+12*StrictMath.sin(x/100)));
        assertFalse(road.routes().isEmpty());
        for(var c:road.columns())assertTrue(Math.abs(c.deckY()-(Math.floor(64+12*StrictMath.sin((c.x()+.5)/100))-1))<=settings.config().roads().maximumEarthwork());
    }
    @Test void riverCrossingKeepsWaterBelowTheDeckAndDoesNotBecomeAnEmbankment() {
        var settings=settings("",new Destination("end",160,0,true));
        var road=plan(settings,(x,z)->x>=70&&x<=86?new MacroSample(59,63,WaterKind.RIVER,false,"river","plains","test"):land(64));
        assertFalse(road.routes().isEmpty());assertTrue(road.columns().stream().anyMatch(RoadPlan.Column::bridge));
        for(var c:road.columns())if(c.bridge()){assertTrue(c.bottomY()>63);assertEquals(c.deckY()-1,c.bottomY());}
        assertTrue(road.routes().getFirst().points().stream().filter(p->p.x()>70&&p.x()<86).allMatch(p->Math.abs(p.z()-.5)<1e-6));
    }
    @Test void oceanCannotBeBridgedAndOptionalFailureIsRecorded() {
        var settings=settings(",\"maximum_operations\":2000000",new Destination("wet",80,0,false));
        var road=plan(settings,(x,z)->x>50?new MacroSample(40,64,WaterKind.OCEAN,false,"ocean","plains","test"):land(64));
        assertTrue(road.routes().isEmpty());assertEquals("NO_VALID_BIOME_WAYPOINT",road.skipped().getFirst().reason());
        assertThrows(PlanningFailure.class,()->plan(settings("",new Destination("wet",80,0,true)),(x,z)->new MacroSample(40,64,WaterKind.OCEAN,false,"ocean","plains","test")));
    }
    @Test void boundedSearchGoesAroundAnObstacleRatherThanSmoothingThroughIt() {
        var road=plan(settings("",new Destination("end",128,0,true)),(x,z)->x>40&&x<88&&Math.abs(z)<20?new MacroSample(64,Double.NaN,WaterKind.NONE,true,"hazard","plains","test"):land(64));
        assertFalse(road.routes().isEmpty());assertTrue(road.columns().stream().noneMatch(c->c.x()>40&&c.x()<87&&Math.abs(c.z())<19));
    }
    @Test void aLoopIsAddedOnlyWhenItFitsTheLengthBudgetAndShortensTravel() {
        var road=plan(settings(",\"loop_budget_fraction\":0.5",new Destination("a",100,0,true),new Destination("b",100,100,true),new Destination("c",0,100,true)),(x,z)->land(64));
        assertEquals(4,road.routes().size());assertTrue(road.columns().stream().anyMatch(RoadPlan.Column::shoulder));
    }
    @Test void targetOrderAndQueryCacheDoNotChangeRoads() {
        var a=new Destination("a",-160,0,true);var b=new Destination("b",0,160,true);
        assertEquals(plan(settings("",a,b),(x,z)->land(64)),plan(settings("",b,a),(x,z)->land(64)));
    }
    @Test void searchBudgetIsExplicitAndCannotSilentlyDropARequiredDestination() {
        var failure=assertThrows(PlanningFailure.class,()->plan(settings(",\"maximum_operations\":1",new Destination("end",160,0,true)),(x,z)->land(64)));
        assertEquals(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED,failure.code());assertEquals(FailureStage.ROADS,failure.failureStage());
    }
    @Test void structuresRequireAnExplicitPureAccessContract() {
        var config=new AdventureWorldConfigParser().parse("""
            {"world":{"radius":512},"spawn":{"biome":"test:plains"},"biomes":{"filler":["test:plains"]},"roads":{"enabled":true},
             "structures":[{"id":"test:keep","adventure_level":1,"count":{"min":1,"max":1},"allowed_biomes":{"id":["test:plains"]},"road":{"enabled":true,"required":true}}]}
            """);
        var placement=new PlannedStructurePlacement("keep/0",new ContentId("test:keep"),200,0);
        assertThrows(PlanningFailure.class,()->new RoadPlanner(1,config,(x,z)->land(64)).plan(
                new AdventurePlanView.SpawnPosition(.5,64,.5,0),List.of(),List.of(placement),StructurePlanningCatalog.fromIds(List.of(placement.structureId())),(x,z)->new ContentId("test:plains")));
        var road=new RoadPlanner(1,config,(x,z)->land(64)).plan(new AdventurePlanView.SpawnPosition(.5,64,.5,0),List.of(),List.of(placement),
                StructurePlanningCatalog.of(List.of(new StructurePlanningInfo(placement.structureId(),new StructurePlanningInfo.RoadAccess(24,48)))),(x,z)->new ContentId("test:plains"));
        assertFalse(road.routes().isEmpty());assertTrue(road.columns().stream().noneMatch(c->Math.abs(c.x()-200)<=24&&Math.abs(c.z())<=24));
    }
    @Test void spawnRemainsTheOnlyRootWithoutSelectedDestinations() {
        var road=plan(settings(""),(x,z)->land(64));
        assertEquals(List.of(new RoadPlan.Node("spawn",0,0,true)),road.nodes());assertTrue(road.routes().isEmpty());
    }
    @Test void repeatedBiomeRequirementsShareOneDestinationAndRequiredWins() {
        var f=settings("",new Destination("end",160,0,false),new Destination("end",160,0,true));
        var road=plan(f,(x,z)->land(64));assertEquals(2,road.nodes().size());
        assertTrue(road.nodes().stream().filter(n->n.id().equals("biome/test:end")).findFirst().orElseThrow().required());
    }
}
