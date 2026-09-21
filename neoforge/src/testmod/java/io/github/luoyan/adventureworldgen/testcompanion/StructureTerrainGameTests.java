package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.worldgen.StructureTerrain;

import io.github.luoyan.adventureworldgen.testcompanion.StructureFixture;
import io.github.luoyan.adventureworldgen.worldgen.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;

@GameTestHolder("testcompanion_structure")
@PrefixGameTestTemplate(false)
public final class StructureTerrainGameTests {
    @GameTest(templateNamespace = "testcompanion_structure", template = "empty", timeoutTicks = 1200)
    public static void fillFlattenNoneAndNativeDensityStayDistinct(GameTestHelper helper) {
        var registered = helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(ResourceLocation.parse("testcompanion:execution_java"));
        var start = new StructureStart(registered, new ChunkPos(0, 0), 0,
                new PiecesContainer(List.of(new StructureFixture.Piece(new BoundingBox(14,100,14,20,102,20)))));
        var holder = (ExecutionDataHolder) (Object) start;
        var support = new Foundation(14,14,20,20,100);
        holder.adventureworldgen$setExecutionData(new StructureExecutionData("fill", new TerrainSettings(TerrainSettings.Mode.FILL, 12), List.of(support)));
        var fill = terrain(helper, start);
        helper.assertTrue(fill.surfaceAt(16,16,80) == 100 && fill.surfaceAt(16,16,120) == 120, "fill cut existing high terrain");
        holder.adventureworldgen$setExecutionData(new StructureExecutionData("flatten", new TerrainSettings(TerrainSettings.Mode.FLATTEN, 12), List.of(support)));
        var flatten = terrain(helper, start);
        helper.assertTrue(flatten.surfaceAt(16,16,80) == 100 && flatten.surfaceAt(16,16,120) == 100, "flatten did not cut and fill");
        helper.assertTrue(flatten.surfaceAt(33,16,80) == 80, "foundation exceeded its margin");
        helper.assertTrue(start.getBoundingBox().intersects(32,16,47,31), "terrain-only neighbouring chunk would miss its reference");
        holder.adventureworldgen$setExecutionData(new StructureExecutionData("none", new TerrainSettings(TerrainSettings.Mode.NONE, 12), List.of()));
        helper.assertTrue(terrain(helper, start).isEmpty(), "none still modified terrain");
        for (var adjustment : List.of(TerrainAdjustment.BURY, TerrainAdjustment.ENCAPSULATE)) {
            var structure = new StructureFixture(new Structure.StructureSettings(registered.biomes(), java.util.Map.of(),
                    GenerationStep.Decoration.SURFACE_STRUCTURES, adjustment));
            var nativeStart = new StructureStart(structure, new ChunkPos(0,0), 0, new PiecesContainer(start.getPieces()));
            ((ExecutionDataHolder) (Object) nativeStart).adventureworldgen$setExecutionData(new StructureExecutionData("native", TerrainSettings.NATIVE, List.of()));
            var nativeTerrain = terrain(helper, nativeStart);
            BlockState[] blocks = new BlockState[384];
            for (int i = 0; i < blocks.length; i++) blocks[i] = i-64 < 96 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            var column = new NoiseColumn(-64, blocks);
            nativeTerrain.applyNativeDensity(column,16,16,96,-64,320);
            helper.assertTrue(column.getBlock(100).blocksMotion(), "native " + adjustment + " kernel was ignored");
            helper.assertTrue(column.getBlock(160).isAir(), "native kernel changed distant terrain");
            helper.assertTrue(nativeTerrain.surfaceAt(16,16,96) == 96, "buried structure was flattened into a surface terrace");
        }
        helper.succeed();
    }
    private static StructureTerrain terrain(GameTestHelper helper, StructureStart start) {
        var manager = new StructureManager(helper.getLevel(), new WorldOptions(0,true,false), null) {
            @Override public List<StructureStart> startsForStructure(ChunkPos pos, java.util.function.Predicate<Structure> predicate) {
                return predicate.test(start.getStructure()) ? List.of(start) : List.of();
            }
        };
        return new StructureTerrain(manager, new ChunkPos(1,1));
    }
}
