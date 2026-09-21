package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.plan.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerTest {
    @Test void spawnAirClearanceDoesNotRaiseTheRoadAboveNaturalGround() {
        var fixture=settings("",new Destination("end",160,0,true));
        var plan=new RoadPlanner(472,fixture.config(),(x,z)->land(64.8)).plan(
                new AdventurePlanView.SpawnPosition(.5,66,.5,0),fixture.patches(),List.of(),StructurePlanningCatalog.fromIds(List.of()),
                (x,z)->new ContentId("test:end"));
        assertFalse(plan.columns().isEmpty());
        assertTrue(plan.columns().stream().allMatch(c->c.deckY()==63),"spawn safety air height became a road bump");
    }

    @Test void tentativeJunctionHeightsCanRiseWithinEarthworkBeforePublication() {
        var settings=settings("").config().roads();
        var existing=new TreeMap<Long,RoadPlan.Column>();
        for(int x=0;x<=40;x++)for(int z=-1;z<=1;z++)
            existing.put(RoadPlan.key(x,z),new RoadPlan.Column(x,z,60,59,63,false));
        var result=RoadConstruction.build(List.of(new io.github.luoyan.adventureworldgen.spatial.Vec2(20.5,.5),
                        new io.github.luoyan.adventureworldgen.spatial.Vec2(20.5,24.5)),settings,
                (x,z)->land(z>=2?68:64),List.of(),existing,-100,-100,63);
        assertTrue(result.valid(),result.failure());
        assertTrue(result.columns().stream().anyMatch(c->existing.containsKey(RoadPlan.key(c.x(),c.z()))&&c.deckY()>60),
                "a feasible junction must be allowed to raise tentative roads");
        assertTrue(existing.values().stream().allMatch(c->c.deckY()==60),"candidate evaluation mutated committed input");
        for(var c:result.columns())assertTrue(Math.abs(c.deckY()-(c.z()+.5>=2?67:63))<=settings.maximumEarthwork());
    }

    @Test void resolvedInstanceBoundsReplaceTheConservativeTypeEnvelope() {
        var id=new ContentId("test:keep");
        var placement=new PlannedStructurePlacement("keep/0",id,240,0);
        var actual=new BoundsXZ(-12,-8,22,18);
        var declared=new StructurePlanningInfo(id,new BoundsXZ(-128,-128,128,128),new StructurePlanningInfo.RoadAccess(4));
        var catalog=StructurePlanningCatalog.of(List.of(declared)).withInstances(Map.of("keep/0",
                new StructurePlanningInfo(id,actual,declared.roadAccess())));
        var road=new RoadPlanner(17,structureConfig(true),(x,z)->land(64)).plan(new AdventurePlanView.SpawnPosition(.5,64,.5,0),
                List.of(new PlannedBiomePatch("end",new ContentId("test:end"),1,398,-2,406,6)),List.of(placement),catalog,
                (x,z)->new ContentId("test:end"));
        assertEquals(actual.translate(240,0),road.reservations().getFirst().bounds());
        var node=road.nodes().stream().filter(n->n.id().equals("structure/keep/0")).findFirst().orElseThrow();
        var box=actual.translate(240,0);
        assertTrue(Math.hypot(Math.max(Math.max(box.minX()-node.x(),node.x()-box.maxX()),0),
                Math.max(Math.max(box.minZ()-node.z(),node.z()-box.maxZ()),0))<=9);
        assertEquals(declared,catalog.find(id).orElseThrow(),"instance refinement must not mutate author facts");
    }

    @Test void lockedEntrancesDoNotAppendLongRectangularSegmentsAfterShaping() {
        var road=structurePlan(true,(x,z)->land(64),new BoundsXZ(-80,-100,80,100));
        assertEquals(2,road.routes().size());
        for(var route:road.routes())for(int i=1;i<route.points().size();i++) {
            var a=route.points().get(i-1);var b=route.points().get(i);
            assertFalse(RoadShape.distance(a,b)>80&&(a.x()==b.x()||a.z()==b.z()),"unshaped rectangle edge: "+a+" -> "+b);
        }
    }

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
                StructurePlanningCatalog.of(List.of(new StructurePlanningInfo(placement.structureId(),new BoundsXZ(-24,-24,24,24),new StructurePlanningInfo.RoadAccess(24)))),(x,z)->new ContentId("test:plains"));
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
    static AdventureWorldConfig structureConfig(boolean connect) {
        return new AdventureWorldConfigParser().parse("""
            {"world":{"radius":512},"spawn":{"biome":"test:plains"},
             "biomes":{"filler":["test:plains"],"required":[{"id":"test:end","adventure_level":1,"road":{"enabled":true,"required":true}}]},
             "roads":{"enabled":true},"structures":[{"id":"test:keep","adventure_level":1,"count":{"min":1,"max":1},
             "allowed_biomes":{"id":["test:plains"]},"road":{"enabled":%s,"required":%s}}]}
            """.formatted(connect,connect));
    }
    static RoadPlan structurePlan(boolean connect,MacroTerrain terrain,BoundsXZ footprint) {
        var id=new ContentId("test:keep");
        return new RoadPlanner(17,structureConfig(connect),terrain).plan(new AdventurePlanView.SpawnPosition(.5,64,.5,0),
                List.of(new PlannedBiomePatch("end",new ContentId("test:end"),1,398,-2,406,6)),
                List.of(new PlannedStructurePlacement("keep/0",id,240,0)),
                StructurePlanningCatalog.of(List.of(new StructurePlanningInfo(id,footprint,connect?new StructurePlanningInfo.RoadAccess(4):null))),
                (x,z)->new ContentId("test:end"));
    }
    @Test void unconnectedStructuresStillReserveTheirAsymmetricFootprint() {
        var footprint=new BoundsXZ(-30,-20,60,30);
        var road=structurePlan(false,(x,z)->land(64),footprint);
        assertEquals(footprint.translate(240,0),road.reservations().getFirst().bounds());
        assertTrue(road.nodes().stream().noneMatch(n->n.id().startsWith("structure/")));
        assertTrue(road.columns().stream().noneMatch(c->footprint.translate(240,0).contains(c.x()+.5,c.z()+.5,0)));
    }
    @Test void blockedNearSideFallsBackToAnotherAccessAndLocksAllIncidentEdges() {
        MacroTerrain terrain=(x,z)-> {
            boolean enclosure=x>=198&&x<=220&&z>=-40&&z<=40;
            boolean wall=enclosure&&(x<202||x>217||z< -36||z>36);
            return new MacroSample(64,Double.NaN,WaterKind.NONE,wall,"plain","plains","test");
        };
        var road=structurePlan(true,terrain,new BoundsXZ(-20,-20,20,20));
        var node=road.nodes().stream().filter(n->n.id().equals("structure/keep/0")).findFirst().orElseThrow();
        assertNotEquals(212,node.x(),"the isolated west-side candidates must not be committed");
        for(var route:road.routes()) {
            if(route.from().equals(node.id()))assertEquals(new io.github.luoyan.adventureworldgen.spatial.Vec2(node.x()+.5,node.z()+.5),route.points().getFirst());
            if(route.to().equals(node.id()))assertEquals(new io.github.luoyan.adventureworldgen.spatial.Vec2(node.x()+.5,node.z()+.5),route.points().getLast());
        }
        assertEquals(road,structurePlan(true,terrain,new BoundsXZ(-20,-20,20,20)));
    }

    @Test void declaredEntranceFreezesAnOutwardLocalConnectorAndCannotEnterTheReservation() {
        var id=new ContentId("test:keep");var placement=new PlannedStructurePlacement("keep/0",id,240,0);
        var access=new StructurePlanningInfo.RoadAccess(4,16,List.of(new StructurePlanningInfo.AccessPoint(0,-28,StructurePlanningInfo.Facing.NORTH)));
        var info=new StructurePlanningInfo(id,new BoundsXZ(-20,-20,20,20),access);
        var road=new RoadPlanner(1,structureConfig(true),(x,z)->land(64)).plan(new AdventurePlanView.SpawnPosition(.5,64,.5,0),
                List.of(new PlannedBiomePatch("end",new ContentId("test:end"),1,398,-2,406,6)),List.of(placement),StructurePlanningCatalog.of(List.of(info)),
                (x,z)->new ContentId("test:end"));
        var node=road.nodes().stream().filter(n->n.id().equals("structure/keep/0")).findFirst().orElseThrow();
        assertEquals(240,node.x());assertEquals(-28,node.z());
        var endpoint=new io.github.luoyan.adventureworldgen.spatial.Vec2(240.5,-27.5);
        var approach=new io.github.luoyan.adventureworldgen.spatial.Vec2(240.5,-43.5);
        for(var route:road.routes()) {
            if(route.from().equals(node.id())){assertEquals(endpoint,route.points().getFirst());assertEquals(approach,route.points().get(1));}
            if(route.to().equals(node.id())){assertEquals(endpoint,route.points().getLast());assertEquals(approach,route.points().get(route.points().size()-2));}
        }
        var bad=new StructurePlanningInfo(id,info.footprint(),new StructurePlanningInfo.RoadAccess(4,16,
                List.of(new StructurePlanningInfo.AccessPoint(0,0,StructurePlanningInfo.Facing.NORTH))));
        assertTrue(RoadAccessCandidates.generate(bad,1,placement,3).isEmpty(),"a declared entrance must not bypass the structure reservation");
    }
    @Test void templateEntrancesRotateWithTheirGeometry() {
        var bounds=new BoundsXZ(-20,-20,20,20);
        var info=new StructurePlanningInfo(new ContentId("test:keep"),null,
                new StructurePlanningInfo.RoadAccess(4,16,List.of(new StructurePlanningInfo.AccessPoint(0,-28,StructurePlanningInfo.Facing.NORTH))),
                new TemplateFootprint(List.of(bounds),List.of(bounds),List.of(1)));
        var point=RoadAccessCandidates.generate(info,1,new PlannedStructurePlacement("test",info.structureId(),-33,-17),3).getFirst();
        assertEquals(new io.github.luoyan.adventureworldgen.spatial.Vec2(-4.5,-16.5),point.endpoint());
        assertEquals(new io.github.luoyan.adventureworldgen.spatial.Vec2(11.5,-16.5),point.approach());
    }

    @Test void optionalIslandCannotConsumeTheRequiredBackboneBudget() {
        var raw=com.google.gson.JsonParser.parseString(CanonicalConfigJson.write(structureConfig(true))).getAsJsonObject();
        raw.getAsJsonObject("biomes").getAsJsonArray("required").get(0).getAsJsonObject().getAsJsonObject("road").addProperty("required",false);
        raw.getAsJsonObject("roads").addProperty("maximum_operations",250_000);
        var config=new AdventureWorldConfigParser().parse(raw.toString());
        var id=new ContentId("test:keep");
        MacroTerrain terrain=(x,z)-> {
            double distance=StrictMath.hypot(x-400,z);
            return distance>=12&&distance<=30?new MacroSample(50,64,WaterKind.LAKE,false,"lake","plains","test"):land(64);
        };
        var road=new RoadPlanner(1,config,terrain).plan(new AdventurePlanView.SpawnPosition(.5,64,.5,0),
                List.of(new PlannedBiomePatch("end",new ContentId("test:end"),1,398,-2,406,6)),
                List.of(new PlannedStructurePlacement("keep/0",id,240,0)),
                StructurePlanningCatalog.of(List.of(new StructurePlanningInfo(id,new BoundsXZ(-20,-20,20,20),new StructurePlanningInfo.RoadAccess(4)))),
                (x,z)->new ContentId("test:end"));
        assertTrue(road.routes().stream().anyMatch(r->r.to().equals("structure/keep/0")||r.from().equals("structure/keep/0")));
        assertTrue(road.skipped().stream().anyMatch(r->r.id().equals("biome/test:end")));
        assertTrue(road.operations()<=250_000);
    }

}
