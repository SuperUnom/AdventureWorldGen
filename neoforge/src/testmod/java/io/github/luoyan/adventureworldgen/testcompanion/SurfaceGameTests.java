package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.worldgen.AdventureChunkGenerator;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import io.github.luoyan.adventureworldgen.plan.ContentId;

@GameTestHolder("testcompanion")
@PrefixGameTestTemplate(false)
public final class SurfaceGameTests {
    /**
     * Historical target of this fixture: the r21 terrain revision the native surface handover was
     * validated against. This suite checks the surface pipeline against a hand-built plan, not
     * against the current production terrain revision, so the string is deliberately the old one -
     * do not "align" it with {@code PlanVersions.TERRAIN} without re-validating the fixture, which
     * would change the frozen plan bytes it produces.
     */
    private static final String HISTORICAL_SURFACE_TERRAIN = "terrain-r21";

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 1200)
    public static void everyTerrainRecipeReachesNativeChunkSurface(GameTestHelper helper) {
        for(var recipe:io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.values()) {
            var weights=new java.util.StringJoiner(",");
            for(var t:io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.values())
                weights.add("\""+t.id()+"\":{\"weight\":"+(t==recipe?1:0)+"}");
            var config=new AdventureWorldConfigParser().parse("""
                    {"world":{"radius":512,"terrain":{"composite":false,"mountain_ranges":false,"templates":{%s}}},
                     "spawn":{"biome":"minecraft:plains"},"biomes":{"filler":["minecraft:plains"]}}
                    """.formatted(weights));
            var plan=new GeneratedAdventurePlan(8844,config,
                    new Coastline(List.of(new Vec2(-512,-512),new Vec2(512,-512),new Vec2(512,512),new Vec2(-512,512))),
                    new RiverNetwork(List.of(),List.of(),"recipe-surface-test"),64,128,256,HISTORICAL_SURFACE_TERRAIN,null);
            var id=ResourceLocation.fromNamespaceAndPath("testcompanion","recipe_"+recipe.id());
            RuntimePlanRegistry.start(new ContentId(id.toString()),()->plan).join();
            var registries=helper.getLevel().registryAccess();
            var generator=new AdventureChunkGenerator(id,registries.lookupOrThrow(Registries.BIOME),
                    registries.lookupOrThrow(Registries.NOISE_SETTINGS),registries.lookupOrThrow(Registries.NOISE));
            for(var pos:List.of(new ChunkPos(8,0),new ChunkPos(-8,8))) {
                var chunk=generate(helper,generator,pos);
                generator.buildPlannedSurface(registries,chunk);
                for(int dx=0;dx<16;dx++)for(int dz=0;dz<16;dz++) {
                    int x=pos.getMinBlockX()+dx,z=pos.getMinBlockZ()+dz;
                    var sample=plan.terrainAt(x+.5,z+.5);
                    helper.assertTrue(sample.recipe().equals(recipe.id()),"chunk uses wrong terrain recipe");
                    int expected=plan.solidSurfaceAt(x,z,sample)-1;
                    helper.assertTrue(chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,dx,dz)==expected,
                            "native surface disagrees with "+recipe.id()+" height field");
                }
            }
        }
        helper.succeed();
    }
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 1200)
    public static void nativeMaterialsAndSurfaceExtensions(GameTestHelper helper) {
        var expected = java.util.Map.of(
                "mushroom_fields", Blocks.MYCELIUM,
                "old_growth_pine_taiga", Blocks.PODZOL,
                "desert", Blocks.SAND,
                "eroded_badlands", Blocks.RED_SAND);
        for (var entry : expected.entrySet()) {
            String biome = "minecraft:" + entry.getKey();
            var config = new AdventureWorldConfigParser().parse("""
                    {"world":{"radius":512},"spawn":{"biome":"%s"},
                     "biomes":{"filler":["%s"]}}
                    """.formatted(biome, biome));
            var plan = new GeneratedAdventurePlan(7331, config,
                    new Coastline(List.of(new Vec2(-512, -512), new Vec2(512, -512),
                            new Vec2(512, 512), new Vec2(-512, 512))),
                    new RiverNetwork(List.of(), List.of(), "surface-test"), 64, 128, 256, "surface-test", null);
            var id = ResourceLocation.fromNamespaceAndPath("testcompanion", "surface_" + entry.getKey());
            RuntimePlanRegistry.start(new ContentId(id.toString()), () -> plan).join();
            var registries = helper.getLevel().registryAccess();
            var generator = new AdventureChunkGenerator(id, registries.lookupOrThrow(Registries.BIOME),
                    registries.lookupOrThrow(Registries.NOISE_SETTINGS), registries.lookupOrThrow(Registries.NOISE));
            var materials = new HashSet<Block>();
            int lowColumns = 0, paintedLowColumns = 0, raisedColumns = 0;
            for (int x : new int[]{-496, -256, 0, 256, 480}) for (int z : new int[]{-496, 0, 480}) {
                var chunk = generate(helper, generator, new ChunkPos(x >> 4, z >> 4));
                var before = new int[256];
                for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++)
                    before[dx * 16 + dz] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, dx, dz);
                // The surface stage must respect existing non-stone blocks, including third-party work.
                var marker = new BlockPos(x, before[0], z);
                chunk.setBlockState(marker, Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
                generator.buildPlannedSurface(registries, chunk);
                helper.assertTrue(chunk.getBlockState(marker).is(Blocks.DIAMOND_BLOCK), "surface overwrote a non-stone block");
                for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
                    int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, dx, dz);
                    if (entry.getKey().equals("eroded_badlands")) {
                        if (top > before[dx * 16 + dz]) raisedColumns++;
                    } else {
                        helper.assertTrue(top == before[dx * 16 + dz], "native surface changed planned height in " + biome);
                        helper.assertTrue(top == plan.solidSurfaceAt(x + dx, z + dz,
                                plan.terrainAt(x + dx + .5, z + dz + .5)) - 1, "column disagrees with planner");
                    }
                    helper.assertTrue(chunk.getNoiseBiome((x + dx) >> 2, top >> 2, (z + dz) >> 2)
                            .is(ResourceLocation.parse(biome)), "surface changed biome ownership");
                    var material = chunk.getBlockState(new BlockPos(x + dx, top, z + dz)).getBlock();
                    materials.add(material);
                    if (top < 70) {
                        lowColumns++;
                        if (material != Blocks.STONE && material != Blocks.DIAMOND_BLOCK) paintedLowColumns++;
                    }
                }
            }
            helper.assertTrue(materials.contains(entry.getValue()), "missing native material for " + biome + ": " + materials);
            helper.assertTrue(lowColumns > 100, "test did not cover low planned terrain");
            helper.assertTrue(paintedLowColumns > 100, "low terrain was treated as underground in " + biome);
            if (entry.getKey().equals("eroded_badlands"))
                helper.assertTrue(raisedColumns > 0, "native badlands pillars were suppressed");
            if (entry.getKey().equals("old_growth_pine_taiga"))
                helper.assertTrue(materials.contains(Blocks.COARSE_DIRT), "native taiga surface noise has no variation");
        }
        helper.succeed();
    }

    private static ProtoChunk generate(GameTestHelper helper, AdventureChunkGenerator generator, ChunkPos pos) {
        var level = helper.getLevel();
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
        var random = level.getChunkSource().randomState();
        generator.createBiomes(random, Blender.empty(), level.structureManager(), chunk).join();
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        var filled = generator.fillFromNoise(Blender.empty(), random, level.structureManager(), chunk);
        level.getServer().managedBlock(filled::isDone); filled.join();
        return chunk;
    }
}
