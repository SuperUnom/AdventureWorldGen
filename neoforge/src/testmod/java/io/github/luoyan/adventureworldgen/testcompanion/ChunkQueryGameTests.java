package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.worldgen.AdventureChunkGenerator;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("testcompanion_performance")
@PrefixGameTestTemplate(false)
public final class ChunkQueryGameTests {
    @GameTest(templateNamespace = "testcompanion_performance", template = "empty", timeoutTicks = 2400)
    public static void shelfOceanPreservesNativeCaves(GameTestHelper helper) {
        AdventureWorldGameTests.plannedOceanColumnsPreserveDeepCavesAndContinuousWater(helper);
    }

    @GameTest(templateNamespace = "testcompanion_performance", template = "empty", timeoutTicks = 1200)
    public static void nativeMaterialsStillMatchPlan(GameTestHelper helper) {
        SurfaceGameTests.nativeMaterialsAndSurfaceExtensions(helper);
    }

    @GameTest(templateNamespace = "testcompanion_performance", template = "empty", timeoutTicks = 1200)
    public static void allRecipesStillMatchPlan(GameTestHelper helper) {
        SurfaceGameTests.everyTerrainRecipeReachesNativeChunkSurface(helper);
    }

    @GameTest(templateNamespace = "testcompanion_performance", template = "empty", timeoutTicks = 1200)
    public static void cachedQueriesPreserveColumnsAndSurfaces(GameTestHelper helper) {
        var config = new AdventureWorldConfigParser().parse("""
                {"world":{"radius":256},"spawn":{"biome":"minecraft:plains"},
                 "biomes":{"filler":["minecraft:plains"]}}
                """);
        var plan = new GeneratedAdventurePlan(7331, config,
                new Coastline(List.of(new Vec2(-256,-256),new Vec2(256,-256),
                        new Vec2(256,256),new Vec2(-256,256))),
                new RiverNetwork(List.of(new RiverNetwork.Channel("query-river", 0, null,
                        List.of(new Vec2(-128, 0), new Vec2(128, 0)), List.of(0.0, 256.0),
                        List.of(76.0, 76.0),
                        new io.github.luoyan.adventureworldgen.hydrology.HydrologyProfile.RiverShape(5, 2, 6, 20, 12, 0.75),
                        null)), List.of(), "query-test"), 64, 128, 256, "query-test", null);
        var id = ResourceLocation.fromNamespaceAndPath("testcompanion_performance", "columns");
        RuntimePlanRegistry.start(id, () -> plan).join();
        var level = helper.getLevel();
        var registries = level.registryAccess();
        var generator = new AdventureChunkGenerator(id, registries.lookupOrThrow(Registries.BIOME),
                registries.lookupOrThrow(Registries.NOISE_SETTINGS), registries.lookupOrThrow(Registries.NOISE));
        var random = level.getChunkSource().randomState();
        helper.assertTrue(plan.terrainAt(.5, .5).wet(), "height test must exercise inland water");
        var noStructures = new net.minecraft.world.level.StructureManager(level,
                new net.minecraft.world.level.levelgen.WorldOptions(plan.seed(), false, false), null) {
            @Override public java.util.List<net.minecraft.world.level.levelgen.structure.StructureStart> startsForStructure(
                    ChunkPos pos, java.util.function.Predicate<net.minecraft.world.level.levelgen.structure.Structure> predicate) {
                return List.of();
            }
        };
        // Interior, negative coordinates, coast transition and full native ocean.
        for (var pos : List.of(new ChunkPos(0,0), new ChunkPos(-8,-8), new ChunkPos(16,0), new ChunkPos(40,0))) {
            var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level,
                    registries.registryOrThrow(Registries.BIOME), null);
            generator.createBiomes(random, Blender.empty(), level.structureManager(), chunk).join();
            chunk.setPersistedStatus(ChunkStatus.BIOMES);
            for (int x = 0; x < 16; x += 3) for (int z = 0; z < 16; z += 3) {
                int bx = pos.getMinBlockX()+x, bz = pos.getMinBlockZ()+z;
                var column = generator.getBaseColumn(bx, bz, chunk, random);
                for (var type : Heightmap.Types.values()) {
                    int expected = AdventureChunkGenerator.MIN_Y;
                    for (int y = 319; y >= AdventureChunkGenerator.MIN_Y; y--)
                        if (type.isOpaque().test(column.getBlock(y))) { expected = y+1; break; }
                    helper.assertTrue(generator.getBaseHeight(bx,bz,type,chunk,random)==expected,
                            "height query differs from full column at " + bx + "," + bz + " " + type);
                }
                var biome = plan.biomeAt(bx, -64, bz);
                helper.assertTrue(biome.equals(plan.biomeAt(bx, 319, bz)), "height changed horizontal biome");
            }
            var filled = generator.fillFromNoise(Blender.empty(), random, noStructures, chunk);
            level.getServer().managedBlock(filled::isDone); filled.join();
            generator.buildPlannedSurface(registries, chunk);
            for (int x=0;x<16;x++)for(int z=0;z<16;z++) {
                int bx=pos.getMinBlockX()+x,bz=pos.getMinBlockZ()+z;
                var sample=plan.terrainAt(bx+.5,bz+.5);
                helper.assertTrue(chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG,x,z)
                        ==plan.solidSurfaceAt(bx,bz,sample)-1,
                        "composed seabed differs from planned shelf at "+bx+","+bz);
            }
        }
        helper.succeed();
    }
}
