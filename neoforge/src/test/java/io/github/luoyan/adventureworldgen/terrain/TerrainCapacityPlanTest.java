package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainCapacityPlanTest {
    @Test void structureAlternativesReserveFeasibleTemplateAndOversizedTargetsFallBackToMinimum() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":1536},"spawn":{"biome":"test:spawn"},"biomes":{
           "filler":["test:spawn"],"terrain_rules":{
             "test:impossible":{"min_height":319},"test:badlands":{"allowed_templates":["badlands"]}}},
           "structures":[{"id":"test:keep","adventure_level":7,"count":{"min":1,"max":1},
             "allowed_biomes":{"id":["test:impossible","test:badlands"],
             "area":{"min":4096,"target":100000000}},"entrance":[0,0,0]}]}
          """);
        var coast=new Coastline(List.of(new Vec2(-1300,-1300),new Vec2(1300,-1300),new Vec2(1300,1300),new Vec2(-1300,1300)));
        var plan=TerrainCapacityPlan.reserve(7331,config,coast,64);
        assertTrue(plan.reservations().stream().anyMatch(r->r.recipe()==TerrainTemplate.BADLANDS));
        assertTrue(plan.reservations().stream().noneMatch(r->r.minHeight()!=null&&r.minHeight()==319));
        long area=plan.reservations().stream().mapToLong(TerrainCapacityPlan.Reservation::reservedArea).sum();
        assertTrue(area>=4096+32768);
        assertTrue(area<100000000,"soft target should yield to finite terrain capacity");
    }

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
        long target=new io.github.luoyan.adventureworldgen.planner.RequirementExpander().expandMinimum(config).patches()
                .stream().mapToLong(d->(long)Math.ceil(d.area().target()*1.25)).sum();
        assertEquals(target,plan.reservations().stream().mapToLong(TerrainCapacityPlan.Reservation::reservedArea).sum());
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
