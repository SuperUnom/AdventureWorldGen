package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainCapacityPlanTest {
    @Test void reservesSharedAreaOnceAndKeepsLowlandHeadroomForErosion() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":3000},"spawn":{"biome":"minecraft:plains"},
           "biomes":{"required":[
            {"id":"minecraft:swamp","adventure_level":2,"area":{"min":16384,"max":32768}},
            {"id":"minecraft:jagged_peaks","adventure_level":7,"area":{"min":16384,"max":32768}}],
            "filler":["minecraft:plains"],"terrain_rules":{
             "minecraft:swamp":{"allowed_terrain":["plains"],"max_height":104},
             "minecraft:jagged_peaks":{"allowed_terrain":["mountains"]}}}}
          """);
        var coast=new Coastline(List.of(new Vec2(-2000,-2000),new Vec2(2000,-2000),new Vec2(2000,2000),new Vec2(-2000,2000)));
        var plan=TerrainCapacityPlan.reserve(7331,config,coast,128);
        assertEquals(65536,plan.reservations().stream().mapToLong(TerrainCapacityPlan.Reservation::reservedArea).sum());
        assertEquals(plan.reservations(),TerrainCapacityPlan.reserve(7331,config,coast,128).reservations());
        assertTrue(plan.reservations().stream().anyMatch(r->r.template()==RegionTerrain.Template.MOUNTAINS));
        var regions=new RegionTerrain(7331,PlannerProfile.V2,plan);
        var terrain=new IslandMacroTerrain(coast,regions,7331,64,128,256,"test");
        int verified=0;
        for(int z=-1900;z<1900;z+=32)for(int x=-1900;x<1900;x+=32) {
            var key=regions.interiorRegionAt(x,z,220); if(key==null)continue;
            var r=plan.at(key); if(r==null||r.maxHeight()==null)continue;
            assertTrue(terrain.sample(x,z).groundSurface()+8<=104+1e-9,"deposition can exceed lowland height contract");
            verified++;
        }
        assertTrue(verified>20);
    }
}
