package io.github.luoyan.adventureworldgen.persistence;

import com.google.gson.JsonParser;
import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.planner.RoadPlanner;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadPersistenceTest {
    @TempDir Path world;
    @Test void nonemptyRoadsRoundTripReadyAndNegativeChunkQueriesWithoutReplanning() throws Exception {
        var config=new AdventureWorldConfigParser().parse("{\"world\":{\"radius\":256},\"spawn\":{\"biome\":\"test:plains\"},\"biomes\":{\"filler\":[\"test:plains\"]}}");
        var base=new GeneratedAdventurePlan(1,config,new Coastline(List.of(new Vec2(-256,-256),new Vec2(256,-256),new Vec2(256,256),new Vec2(-256,256))),new RiverNetwork(List.of(),List.of(),PlannerProfile.V2.hydrologyVersion()),64,32,64,"road-persistence-test",null);
        var roadConfig=new AdventureWorldConfigParser().parse("""
            {"world":{"radius":256},"spawn":{"biome":"test:plains"},"biomes":{"filler":["test:plains"],
             "required":[{"id":"test:end","adventure_level":1,"road":{"enabled":true,"required":true}}]},"roads":{"enabled":true}}
            """);
        var patch=new PlannedBiomePatch("end",new ContentId("test:end"),1,-122,-18,-114,-10);
        var road=new RoadPlanner(1,roadConfig,(x,z)->new MacroSample(64,Double.NaN,WaterKind.NONE,false,"plain","plains","test"))
                .plan(new AdventurePlanView.SpawnPosition(.5,64,.5,0),List.of(patch),List.of(),StructurePlanningCatalog.fromIds(List.of()),(x,z)->patch.biomeId());
        road=new RoadPlan(road.nodes(),road.routes(),road.columns(),List.of(new RoadPlan.Reservation("unconnected",new BoundsXZ(-50,80,-12,95))),road.skipped(),road.operations());
        var b=base.snapshot();var snapshot=new PlanSnapshot(b.seed(),b.diagnostics(),b.spawn(),b.coastline(),b.riverNetwork(),b.seaSurface(),b.landBand(),b.seaBand(),b.terrainVersion(),b.recipeSettings(),b.recipeRegions(),b.biomePatches(),b.structures(),b.erosion(),b.capacities(),b.biomeLayout(),road);
        var codec=new PlanV2Codec();var profile=new ContentId("test:roads");byte[] bytes=codec.encode(profile,"road-input",snapshot);
        var repository=new AtomicPlanRepository();repository.publishAtomically(world,profile,bytes,"road-input");
        var ready=repository.loadReady(world,profile,"road-input").orElseThrow();
        var restored=GeneratedAdventurePlan.restore(config,codec.decode(ready.canonicalPlan(),profile,"road-input"));
        assertEquals(road,restored.roads());assertArrayEquals(bytes,codec.encode(profile,"road-input",restored.snapshot()));
        var shuffled=new ArrayList<>(road.columns());Collections.reverse(shuffled);
        for(var c:shuffled){assertEquals(c,restored.roadAt(c.x(),c.z()));assertTrue(restored.roadsInChunk(Math.floorDiv(c.x(),16),Math.floorDiv(c.z(),16)).contains(c));}
        var bad=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
        bad.getAsJsonObject("roads").getAsJsonArray("columns").add(bad.getAsJsonObject("roads").getAsJsonArray("columns").get(0));
        assertThrows(PlanningFailure.class,()->codec.decode(bad.toString().getBytes(StandardCharsets.UTF_8),profile,"road-input"));
        var old=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();old.addProperty("format","plan-v4");
        assertThrows(PlanningFailure.class,()->codec.decode(old.toString().getBytes(StandardCharsets.UTF_8),profile,"road-input"));
        assertTrue(repository.loadReady(world,profile,"different-road-input").isEmpty());
        var invalid=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
        invalid.getAsJsonObject("roads").getAsJsonArray("reservations").get(0).getAsJsonObject().getAsJsonObject("bounds").addProperty("minX",100);
        assertThrows(PlanningFailure.class,()->codec.decode(invalid.toString().getBytes(StandardCharsets.UTF_8),profile,"road-input"));
    }
}
