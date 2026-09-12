package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.luoyan.adventureworldgen.plan.StableIds;

class RequirementExpanderTest {
    @Test
    void expandsStableMinimumInstancesAndImplicitSpawnPatch() {
        var config = new AdventureWorldConfigParser().parse("""
                {"world":{"radius":6000},"spawn":{"biome":"minecraft:plains","structure":{"id":"minecraft:desert_pyramid","spawn_point":[0,1,0]}},
                 "biomes":{"filler":["minecraft:plains"]},
                 "structures":[{"id":"minecraft:desert_pyramid","adventure_level":0,"count":{"min":0,"max":2},
                   "allowed_biomes":{"id":[]},"entrance":[0,0,0]}]}
                """);

        var expanded = new RequirementExpander().expandMinimum(config);
        assertEquals("patch/spawn", expanded.patches().getFirst().patchId());
        assertTrue(expanded.patches().getFirst().implicit());
        assertEquals(2, expanded.patches().size());
        var carrier=expanded.patches().get(1);
        assertEquals(StableIds.carrierPatch(expanded.structures().getFirst().instanceId()),carrier.patchId());
        assertEquals(config.structures().getFirst().allowedBiomes().area(),carrier.area());
        assertEquals(config.biomes().filler(),carrier.allowedBiomes());
        assertEquals(1, expanded.structures().size());
        assertEquals("instance/minecraft:desert_pyramid/0", expanded.structures().getFirst().instanceId());
        assertTrue(expanded.structures().getFirst().spawnInstance());
    }

    @Test
    void spawnStructureUsesTheExplicitSpawnBiomeAmongAllowedAlternatives() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":512},"spawn":{"biome":"test:plains","structure":{"id":"test:keep","spawn_point":[0,1,0]}},
           "biomes":{"filler":["test:plains"]},"structures":[{"id":"test:keep","adventure_level":0,
           "count":{"min":0,"max":1},"allowed_biomes":{"id":["test:desert","test:plains"]},"entrance":[0,0,0]}]}
          """);
        var expanded=new RequirementExpander().expandMinimum(config);
        var carrier=expanded.patches().stream().filter(d->d.patchId().startsWith("patch/carrier/")).findFirst().orElseThrow();
        assertEquals(java.util.List.of(config.spawn().biome()),carrier.allowedBiomes());
    }

    @Test
    void explicitLevelZeroPatchPreventsDuplicateImplicitPatch() {
        var config = new AdventureWorldConfigParser().parse("""
                {"world":{"radius":6000},"spawn":{"biome":"minecraft:plains"},
                 "biomes":{"required":[{"id":"minecraft:plains","adventure_level":0}],"filler":["minecraft:plains"]}}
                """);
        var expanded = new RequirementExpander().expandMinimum(config);
        assertEquals(1, expanded.patches().size());
        assertFalse(expanded.patches().getFirst().implicit());
    }
}
