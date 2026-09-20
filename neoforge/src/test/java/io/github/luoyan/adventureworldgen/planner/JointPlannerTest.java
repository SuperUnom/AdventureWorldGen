package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.luoyan.adventureworldgen.spatial.CellMask;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.StructurePlanningCatalog;

class JointPlannerTest {
    @Test void softAdventurePreferenceOrdersBiomesWithoutAForbiddenLevelBand() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":512},"spawn":{"biome":"minecraft:plains"},"biomes":{
           "required":[{"id":"minecraft:forest","adventure_level":2,"area":{"min":4096,"max":8192}},
                       {"id":"minecraft:taiga","adventure_level":8,"area":{"min":4096,"max":8192}}],
           "filler":["minecraft:plains"]}}
          """);
        MacroTerrain flat=(x,z)->new MacroSample(80,Double.NaN,WaterKind.NONE,false,"r","plains","test");
        var preference=new JointPlanner.LevelConstraint() {
            public boolean accepts(int level,int x,int z){return true;}
            public double penalty(int level,int x,int z){return io.github.luoyan.adventureworldgen.cost.AdventurePreference.penalty(level,10*StrictMath.hypot(x,z)/512);}
        };
        var result=new JointPlanner(PlannerProfile.V2).plan(7331,config,flat,catalog(config),preference);
        var early=result.patches().stream().filter(p->p.adventureLevel()==2).findFirst().orElseThrow();
        var late=result.patches().stream().filter(p->p.adventureLevel()==8).findFirst().orElseThrow();
        double a=10*StrictMath.hypot(early.anchorX(),early.anchorZ())/512,b=10*StrictMath.hypot(late.anchorX(),late.anchorZ())/512;
        assertTrue(b>a+3,"penalty failed to create a progression in biome placement");
        assertTrue(Math.abs(a-2)<2&&Math.abs(b-8)<2,"soft preference has no useful attraction to its target");
    }

    @Test void smallRequiredBiomesRetainAConnectedCore() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":256},"spawn":{"biome":"minecraft:forest"},"biomes":{
           "required":[{"id":"minecraft:forest","adventure_level":0,"area":{"min":16384,"max":32768}}],
           "filler":["minecraft:plains"]}}
          """);
        MacroTerrain flat=(x,z)->new MacroSample(80,Double.NaN,WaterKind.NONE,false,"r","plains","test");
        for(long seed:new long[]{345705185492107788L,4126649097427443736L,1}) {
            var result=new JointPlanner(PlannerProfile.V2).plan(seed,config,flat,catalog(config));
            var patch=result.patches().getFirst();
            assertTrue(patch.area() > 24576 && patch.area() <= 32768,"growth should pursue the soft target after minimum");
            for(int z=-12;z<=12;z+=4)for(int x=-12;x<=12;x+=4)
                assertTrue(patch.contains(x,z),"small required biome lost its central interior");
            var seen=new java.util.HashSet<Long>();var queue=new java.util.ArrayDeque<Long>();
            long origin=CellMask.key(patch.anchorX(),patch.anchorZ());seen.add(origin);queue.add(origin);
            while(!queue.isEmpty()) {
                long cell=queue.removeFirst();int x=CellMask.x(cell),z=CellMask.z(cell);
                for(int[] d:List.of(new int[]{4,0},new int[]{-4,0},new int[]{0,4},new int[]{0,-4})) {
                    long next=CellMask.key(x+d[0],z+d[1]);
                    if(patch.contains(x+d[0],z+d[1])&&seen.add(next))queue.add(next);
                }
            }
            assertTrue(seen.size()*16>=patch.area()*0.97,"required area is scattered outside its main component");
        }
    }

    @Test
    void refinesAnchorsToFourBlocksWithoutImposingAnExtraSeparationRule() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":128},"spawn":{"biome":"minecraft:plains"},"biomes":{
           "required":[{"id":"minecraft:plains","adventure_level":0,"area":{"min":4096,"max":8192}},
                       {"id":"minecraft:forest","adventure_level":4,"area":{"min":4096,"max":8192}}],
           "filler":["minecraft:plains"]}}
          """);
        MacroTerrain flat=(x,z)->new MacroSample(80,Double.NaN,WaterKind.NONE,false,"r","plains","test");
        var plan=new JointPlanner(PlannerProfile.V2).plan(7331,config,flat,
                catalog(config),(level,x,z)->level==0?x==0&&z==0:x==4&&z==4);
        var forest=plan.patches().stream().filter(p->p.biomeId().value().equals("minecraft:forest")).findFirst().orElseThrow();
        assertEquals(4,forest.anchorX()); assertEquals(4,forest.anchorZ());
        assertTrue(forest.area() >= 4096 && forest.area() <= 8192);
        assertTrue(forest.contains(4,4));
    }

    @Test
    void spawnMaskCanMoveAwayFromForbiddenTerrainWhileStillCoveringOrigin() {
        var config = new AdventureWorldConfigParser().parse("""
            {"world":{"radius":3000},"spawn":{"biome":"minecraft:plains"},
             "biomes":{"required":[],"filler":["minecraft:plains","minecraft:jagged_peaks"],
               "terrain_rules":{"minecraft:plains":{"allowed_terrain":["plains","hills","plateau"]},
                 "minecraft:jagged_peaks":{"allowed_terrain":["mountains"]}}}}
            """);
        MacroTerrain terrain = (x,z) -> new MacroSample(80, Double.NaN, WaterKind.NONE, false,
                "r", x > 80 ? "mountains" : "plains", "test");
        var plan = new JointPlanner(PlannerProfile.V2).plan(34, config, terrain,
                catalog(config));
        var spawn = plan.patches().getFirst();
        assertTrue(spawn.contains(0,0));
        assertEquals(0.5, plan.spawn().x());
        assertEquals(0.5, plan.spawn().z());
        assertTrue(spawn.area() >= AdventureWorldConfig.AreaRange.DEFAULT.min());
        for (int z = spawn.minZ(); z < spawn.maxZExclusive(); z += 4)
            for (int x = spawn.minX(); x < spawn.maxXExclusive(); x += 4)
                if (spawn.contains(x,z)) assertTrue(x + 2 <= 80, "spawn biome spills onto forbidden mountain terrain");
    }

    @Test
    void reservesFixedSpawnBeforeNearbyRequiredBiomes() {
        var config = new AdventureWorldConfigParser().parse("""
            {"world":{"radius":1000},"spawn":{"biome":"minecraft:plains"},
             "biomes":{"required":[{"id":"minecraft:forest","adventure_level":1,
               "area":{"min":4096,"max":8192}}],"filler":["minecraft:plains"]}}
            """);
        MacroTerrain flat = (x,z) -> new MacroSample(80, Double.NaN, WaterKind.NONE, false,
                "r", "plains", "test");
        var plan = new JointPlanner(PlannerProfile.V2).plan(34, config, flat,
                catalog(config));
        var spawn = plan.patches().stream().filter(p -> p.patchId().equals("patch/spawn")).findFirst().orElseThrow();
        var forest = plan.patches().stream().filter(p -> p.biomeId().value().equals("minecraft:forest")).findFirst().orElseThrow();
        assertTrue(spawn.contains(0,0));
        assertTrue(spawn.area() >= 65536);
        assertTrue(forest.area() >= 4096);
        for (int z = forest.minZ(); z < forest.maxZExclusive(); z += 4)
            for (int x = forest.minX(); x < forest.maxXExclusive(); x += 4)
                assertTrue(!forest.contains(x,z) || !spawn.contains(x,z), "forest consumed the fixed spawn mask");
    }

    @Test
    void biomeTerrainRulesCoverWholePatchesAndAllowSteepMountainBiomes() {
        var config = new AdventureWorldConfigParser().parse("""
            {"world":{"radius":3000},"spawn":{"biome":"minecraft:plains"},
             "biomes":{"required":[{"id":"minecraft:jagged_peaks","adventure_level":5,"area":{"min":4096,"max":8192}}],
               "filler":["minecraft:plains"],"terrain_rules":{
                 "minecraft:jagged_peaks":{"allowed_terrain":["mountains"]}}}}
            """);
        MacroTerrain terrain = (x,z) -> new MacroSample(x > 500 ? 180 + 15 * StrictMath.sin(x / 20) : 80,
                Double.NaN, WaterKind.NONE, false, "r", x > 500 ? "mountains" : "plains", "v");
        var plan = new JointPlanner(PlannerProfile.V2).plan(123, config, terrain, catalog(config));
        var patch = plan.patches().stream().filter(p -> p.biomeId().value().equals("minecraft:jagged_peaks")).findFirst().orElseThrow();
        for (int z = patch.minZ(); z < patch.maxZExclusive(); z += 4)
            for (int x = patch.minX(); x < patch.maxXExclusive(); x += 4)
                if (patch.contains(x,z)) assertTrue(x + 2 > 500, "mountain biome spills onto plains");
        assertTrue(patch.area() >= 4096);
    }

    @Test
    void producesStableConnectedGridPatchesAndMinimumInstances() {
        var config = new AdventureWorldConfigParser().parse(new StringReader("""
                {"world":{"radius":6000},"spawn":{"biome":"minecraft:plains"},
                 "biomes":{"required":[{"id":"minecraft:forest","adventure_level":2,
                   "area":{"min":65536,"max":131072}}],
                   "filler":["minecraft:desert","minecraft:forest","minecraft:plains"]},
                 "structures":[{"id":"minecraft:desert_pyramid","adventure_level":4,
                   "count":{"min":1,"max":1},"allowed_biomes":{"id":["minecraft:desert"]}}]}
                """));
        MacroTerrain flat = (x, z) -> new MacroSample(80, Double.NaN, WaterKind.NONE, false,
                "region/0/0", "plains", "terrain/test");
        JointPlanner planner = new JointPlanner(PlannerProfile.V2);
        var first = planner.plan(1234, config, flat, catalog(config));
        var second = planner.plan(1234, config, flat, catalog(config));

        assertEquals(first, second);
        assertEquals(3, first.patches().stream().filter(p->!p.patchId().startsWith("filler/")).count()); // explicit, implicit spawn, and structure carrier
        assertEquals(1, first.structures().size());
        var placement=first.structures().getFirst();
        var carrier=first.patches().stream().filter(p->p.patchId().equals(
                io.github.luoyan.adventureworldgen.plan.StableIds.carrierPatch(placement.instanceId()))).findFirst().orElseThrow();
        assertTrue(carrier.contains(placement.anchorX(),placement.anchorZ()));
        assertTrue(first.patches().stream().filter(p->!p.patchId().startsWith("filler/")).allMatch(patch -> patch.area() >= 16_384));
        assertTrue(first.patches().stream().allMatch(patch -> (patch.minX() & 3) == 0 && (patch.minZ() & 3) == 0));
    }

    private static StructurePlanningCatalog catalog(AdventureWorldConfig config) {
        return StructurePlanningCatalog.fromIds(config.structures().stream()
                .map(AdventureWorldConfig.StructureSettings::id).toList());
    }
}
