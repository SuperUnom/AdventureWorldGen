package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.worldgen.structure.StructureAdapterManager;
import io.github.luoyan.adventureworldgen.worldgen.structure.StructurePlacement;
import io.github.luoyan.adventureworldgen.worldgen.structure.StructureResolver;
import io.github.luoyan.adventureworldgen.worldgen.structure.StructureType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("testcompanion_performance")
@PrefixGameTestTemplate(false)
public final class StructureExecutionGameTests {
    @GameTest(templateNamespace = "testcompanion_performance", template = "empty", timeoutTicks = 1200)
    public static void adaptersResolveAndPlaceSupportedStructures(GameTestHelper helper) {
        var level = helper.getLevel();
        var resolver = new StructureResolver();
        var templateId = ResourceLocation.fromNamespaceAndPath(
                "testcompanion_performance", "runtime_structure_adapter_fixture");
        var source = helper.absolutePos(new BlockPos(1, 2, 1));
        var target = helper.absolutePos(new BlockPos(4, 2, 4));

        level.setBlock(source, Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH), 3);
        level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
        level.getStructureManager().getOrCreate(templateId)
                .fillFromWorld(level, source, new Vec3i(1, 1, 1), false, Blocks.STRUCTURE_VOID);
        level.setBlock(source, Blocks.AIR.defaultBlockState(), 3);

        var placement = new StructurePlacement(templateId, target, Rotation.CLOCKWISE_90);
        helper.assertTrue(resolver.resolve(level, placement) == StructureType.TEMPLATE,
                "runtime template was not detected before registered structures");
        helper.assertTrue(resolver.resolve(level, ResourceLocation.parse("minecraft:village_plains"))
                        == StructureType.JIGSAW,
                "vanilla jigsaw structure was not detected");
        helper.assertTrue(resolver.resolve(level, ResourceLocation.parse("minecraft:stronghold"))
                        == StructureType.JAVA_STRUCTURE,
                "vanilla Java structure was not detected");
        helper.assertTrue(resolver.resolve(level, ResourceLocation.parse("testcompanion_performance:missing"))
                        == StructureType.UNKNOWN,
                "missing content did not resolve to UNKNOWN");

        var manager = new StructureAdapterManager();
        manager.generate(level, placement);
        var placed = level.getBlockState(target);
        helper.assertTrue(placed.is(Blocks.OAK_STAIRS), "template block was not placed at the requested position");
        helper.assertTrue(placed.getValue(HorizontalDirectionalBlock.FACING) == Direction.EAST,
                "template rotation was not applied");

        assertUnsupported(helper, () -> manager.generate(level, new StructurePlacement(
                ResourceLocation.parse("minecraft:village_plains"), target, Rotation.NONE)), "jigsaw");
        assertUnsupported(helper, () -> manager.generate(level, new StructurePlacement(
                ResourceLocation.parse("minecraft:stronghold"), target, Rotation.CLOCKWISE_90)),
                "rotated Java structure");

        var hutOrigin = helper.absolutePos(new BlockPos(8, 2, 8));
        manager.generate(level, new StructurePlacement(
                ResourceLocation.parse("minecraft:swamp_hut"), hutOrigin, Rotation.NONE));
        boolean foundHutBlock = false;
        for (int x = hutOrigin.getX(); x <= hutOrigin.getX() + 8 && !foundHutBlock; x++) {
            for (int z = hutOrigin.getZ(); z <= hutOrigin.getZ() + 8 && !foundHutBlock; z++) {
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.SPRUCE_PLANKS)) {
                        foundHutBlock = true;
                        break;
                    }
                }
            }
        }
        helper.assertTrue(foundHutBlock, "ordinary registered Structure did not place its pieces");
        helper.succeed();
    }

    private static void assertUnsupported(GameTestHelper helper, Runnable action, String kind) {
        try {
            action.run();
            helper.fail(kind + " generation should report an unsupported operation");
        } catch (UnsupportedOperationException expected) {
            helper.assertTrue(expected.getMessage().contains("not implemented")
                            || expected.getMessage().contains("does not support"),
                    kind + " failure did not explain the unsupported operation");
        }
    }
}
