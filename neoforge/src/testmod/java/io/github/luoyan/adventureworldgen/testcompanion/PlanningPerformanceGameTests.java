package io.github.luoyan.adventureworldgen.testcompanion;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Production planning and shared macro structure carriers, including READY reload. */
@GameTestHolder("testcompanion_planning")
@PrefixGameTestTemplate(false)
public final class PlanningPerformanceGameTests {
    @GameTest(templateNamespace="testcompanion_planning", template="empty", timeoutTicks=2400)
    public static void productionPlanningAndReload(GameTestHelper helper) {
        AdventureWorldGameTests.productionProfilePlansIrregularContinentAtRadius3000(helper);
    }

    @GameTest(templateNamespace="testcompanion_planning", template="empty", timeoutTicks=2400)
    public static void sharedStructureCarriersPlanAndReload(GameTestHelper helper) {
        var parser = new io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser();
        var config = parser.parse("""
          {"world":{"radius":768},"spawn":{"biome":"minecraft:plains"},"biomes":{
           "required":[{"id":"minecraft:plains","adventure_level":0,
             "area":{"min":4096,"target":16384,"max":32768}}],"filler":["minecraft:plains"]},
           "structures":[{"id":"minecraft:village_plains","adventure_level":2,"count":{"min":2,"max":2},
             "spacing":{"min":32},"allowed_biomes":{"id":["minecraft:plains"],
             "area":{"min":2048,"target":8192,"max":16384}}}]}
          """);
        var id = new io.github.luoyan.adventureworldgen.plan.ContentId("testcompanion:shared-carriers");
        var loaded = new io.github.luoyan.adventureworldgen.config.LoadedProfile(id, config,
                io.github.luoyan.adventureworldgen.config.CanonicalConfigJson.write(config), "shared-carrier-test");
        var directory = java.nio.file.Path.of("shared-carriers");
        var adapters = io.github.luoyan.adventureworldgen.worldgen.MinecraftAdapters.builtIn();
        var plan = io.github.luoyan.adventureworldgen.runtime.RuntimePlanner.plan(7331, loaded, directory, adapters);
        var demands = new io.github.luoyan.adventureworldgen.planner.RequirementExpander().expandMinimum(config);
        helper.assertTrue(demands.patches().size() == 1, "spawn and both carriers did not merge");
        var shared = plan.biomePatches().stream().filter(p -> p.patchId().equals(demands.patches().getFirst().patchId()))
                .findFirst().orElseThrow();
        helper.assertTrue(plan.plannedStructures().size() == 2, "sharing changed the structure count");
        for (var placement : plan.plannedStructures()) {
            helper.assertTrue(shared.contains(placement.anchorX(), placement.anchorZ()), "anchor lost shared ownership");
            helper.assertTrue(helper.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                    .containsKey(net.minecraft.resources.ResourceLocation.parse(placement.structureId().value())),
                    "planned structure is not registered");
        }
        var restored = io.github.luoyan.adventureworldgen.runtime.RuntimePlanner.plan(7331, loaded, directory, adapters);
        helper.assertTrue(io.github.luoyan.adventureworldgen.runtime.PlanningProgress.current().stage()
                == io.github.luoyan.adventureworldgen.plan.PlanningStage.CACHE, "shared READY reran planning");
        helper.assertTrue(plan.biomePatches().equals(restored.biomePatches()), "READY changed shared ownership");
        helper.assertTrue(plan.plannedStructures().equals(restored.plannedStructures()), "READY changed structure anchors");
        for (int z = -256; z <= 256; z += 32) for (int x = -256; x <= 256; x += 32)
            helper.assertTrue(plan.biomeAt(x,64,z).equals(restored.biomeAt(x,64,z)), "READY changed biome queries");
        helper.succeed();
    }
}
