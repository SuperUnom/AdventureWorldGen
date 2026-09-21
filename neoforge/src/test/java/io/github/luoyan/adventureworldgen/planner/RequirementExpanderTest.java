package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.AreaRange;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import java.util.List;
import io.github.luoyan.adventureworldgen.plan.StableIds;

class RequirementExpanderTest {
    @Test
    void expandsStableMinimumInstancesAndImplicitSpawnPatch() {
        var config = new AdventureWorldConfigParser().parse("""
                {"world":{"radius":6000},"spawn":{"biome":"minecraft:plains"},
                 "biomes":{"filler":["minecraft:plains"]},
                 "structures":[{"id":"minecraft:desert_pyramid","adventure_level":0,"count":{"min":1,"max":2},
                   "allowed_biomes":{"id":[]}}]}
                """);

        var expanded = new RequirementExpander().expandMinimum(config);
        assertEquals("patch/spawn", expanded.patches().getFirst().patchId());
        assertTrue(expanded.patches().getFirst().implicit());
        assertEquals(1, expanded.patches().size());
        var carrier=expanded.patches().getFirst();
        assertEquals(carrier.patchId(), expanded.carrierPatchId(expanded.structures().getFirst().instanceId()));
        assertEquals(AreaRange.DEFAULT.min() * 2, carrier.area().min());
        assertEquals(AreaRange.DEFAULT.target() * 2, carrier.area().target());
        assertEquals(Long.MAX_VALUE, carrier.area().max());
        assertTrue(carrier.spawn());
        assertEquals(config.biomes().filler(),carrier.allowedBiomes());
        assertEquals(1, expanded.structures().size());
        assertEquals("instance/minecraft:desert_pyramid/0", expanded.structures().getFirst().instanceId());
        assertTrue(expanded.structures().getFirst().required());
    }

    @Test
    void structureDemandKeepsAuthorAllowedBiomeAlternatives() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":512},"spawn":{"biome":"test:plains"},
           "biomes":{"filler":["test:plains"]},"structures":[{"id":"test:keep","adventure_level":0,
           "count":{"min":1,"max":1},"allowed_biomes":{"id":["test:desert","test:plains"]}}]}
          """);
        var expanded=new RequirementExpander().expandMinimum(config);
        assertEquals(List.of(new ContentId("test:desert"), new ContentId("test:plains")),
                expanded.structures().getFirst().allowedBiomes());
        assertEquals(List.of(new ContentId("test:plains")), expanded.patches().getFirst().allowedBiomes());
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

    @Test
    void mergesSameBiomeWithinTwoLevelsWithoutTransitiveChaining() {
        var four = demand("a", 4, "forest");
        var six = demand("b", 6, "forest");
        var eight = demand("c", 8, "forest");
        var groups = RequirementExpander.merge(List.of(eight, six, four));
        assertEquals(2, groups.size());
        assertEquals(2, groups.getFirst().members().size());
        assertEquals(4, groups.getFirst().minimumLevel());
        assertEquals(6, groups.getFirst().maximumLevel());
        assertEquals(5, groups.getFirst().adventureLevel());
        assertEquals(groups, RequirementExpander.merge(List.of(four, eight, six)));
        assertEquals(2, RequirementExpander.merge(List.of(four, demand("d", 7, "forest"))).size());
        assertEquals(2, RequirementExpander.merge(List.of(four, demand("d", 4, "desert"))).size());
    }

    @Test
    void commonBiomeIntersectionSurvivesWithoutUnionOrFirstCandidateSelection() {
        var a = demand("a", 4, "forest", "desert", "taiga");
        var b = demand("b", 6, "desert", "taiga");
        var group = RequirementExpander.merge(List.of(a, b)).getFirst();
        assertEquals(List.of(new ContentId("test:desert"), new ContentId("test:taiga")), group.allowedBiomes());
        assertEquals(2, RequirementExpander.merge(List.of(a, b, demand("c", 5, "forest"))).size());
    }

    @Test
    void sumsAreaAndRetainsRequiredStructureRoleWhenIdentityBelongsToOrdinaryBiome() {
        var biome = demand("a", 4, "forest");
        var carrier = new RequirementExpander.PatchDemand("b", biome.allowedBiomes(), 6,
                new AreaRange(64, 512, 128), "instance/test:keep/0", true)
                .withRoles(false, List.of("instance/test:keep/0"));
        var merged = RequirementExpander.merge(List.of(carrier, biome)).getFirst();
        assertEquals(new AreaRange(96, 768, 192), merged.area());
        assertEquals("a", merged.patchId());
        assertEquals(List.of("instance/test:keep/0"), merged.structureInstances());
        assertTrue(merged.requiresSeed());
        assertFalse(merged.implicit());
    }

    @Test
    void spawnKeepsLevelZeroWhileRememberingTheFullGroupSpan() {
        var spawn = demand("spawn", 0, "plains").withRoles(true, List.of());
        var group = RequirementExpander.merge(List.of(spawn, demand("b", 2, "plains"))).getFirst();
        assertTrue(group.spawn());
        assertEquals(0, group.adventureLevel());
        assertEquals(2, group.maximumLevel());
        assertFalse(group.canMerge(demand("c", 3, "plains")));
    }

    @Test
    void areaOverflowReportsResourceLimitInsteadOfWrapping() {
        var ordinary = demand("a", 4, "forest");
        var huge = new RequirementExpander.PatchDemand("b", ordinary.allowedBiomes(), 4,
                new AreaRange(Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MAX_VALUE), "b", false);
        var failure = assertThrows(PlanningFailure.class, () -> RequirementExpander.merge(List.of(ordinary, huge)));
        assertEquals(PlanningFailure.Code.RESOURCE_LIMIT, failure.code());
    }

    private static RequirementExpander.PatchDemand demand(String id, int level, String... biomes) {
        return new RequirementExpander.PatchDemand(id,
                java.util.Arrays.stream(biomes).map(b -> new ContentId("test:" + b)).toList(),
                level, new AreaRange(32, 256, 64), id, false);
    }
}
