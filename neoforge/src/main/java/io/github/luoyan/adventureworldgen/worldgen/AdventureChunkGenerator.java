package io.github.luoyan.adventureworldgen.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.core.RegistryAccess;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Current-plan terrain executor. Its serialized form contains only the fixed profile ID. */
public final class AdventureChunkGenerator extends ChunkGenerator {
    public static final MapCodec<AdventureChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("profile").forGetter(AdventureChunkGenerator::profile),
            RegistryOps.<Biome>retrieveRegistryLookup(Registries.BIOME).forGetter(generator -> null),
            RegistryOps.<NoiseGeneratorSettings>retrieveRegistryLookup(Registries.NOISE_SETTINGS).forGetter(generator -> null),
            RegistryOps.<NormalNoise.NoiseParameters>retrieveRegistryLookup(Registries.NOISE).forGetter(generator -> null)
    ).apply(instance, AdventureChunkGenerator::new));

    public static final int MIN_Y = -64;
    public static final int DEPTH = 384;
    public static final int SEA_LEVEL = 64;

    private final ResourceLocation profile;
    private final NoiseBasedChunkGenerator vanillaDelegate;
    private final NoiseBasedChunkGenerator oceanDelegate;
    private final NoiseGeneratorSettings oceanSettings;
    private final SurfaceRules.RuleSource surfaceRule;
    private final HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises;
    private volatile RandomState oceanRandomState;

    public AdventureChunkGenerator(ResourceLocation profile, HolderLookup.RegistryLookup<Biome> biomes,
                                   HolderLookup.RegistryLookup<NoiseGeneratorSettings> noiseSettings,
                                   HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises) {
        this(profile, new AdventureBiomeSource(profile, biomes), noiseSettings, noises);
    }

    private AdventureChunkGenerator(ResourceLocation profile, AdventureBiomeSource biomeSource,
                                    HolderLookup.RegistryLookup<NoiseGeneratorSettings> noiseSettings,
                                   HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises) {
        super(biomeSource);
        this.profile = profile;
        Holder<NoiseGeneratorSettings> overworld = noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        this.vanillaDelegate = new NoiseBasedChunkGenerator(biomeSource, overworld);
        this.surfaceRule = overworld.value().surfaceRule();
        this.noises = noises;
        Holder<NoiseGeneratorSettings> ocean = noiseSettings.getOrThrow(net.minecraft.resources.ResourceKey.create(
                Registries.NOISE_SETTINGS, ResourceLocation.fromNamespaceAndPath("adventureworldgen", "ocean")));
        this.oceanSettings = ocean.value();
        this.oceanDelegate = new NoiseBasedChunkGenerator(biomeSource, ocean);
    }

    public ResourceLocation profile() { return profile; }

    /** The plan-registry key for this generator's serialized profile id. */
    private io.github.luoyan.adventureworldgen.plan.ContentId planKey() {
        return new io.github.luoyan.adventureworldgen.plan.ContentId(profile.toString());
    }
    @Override protected MapCodec<? extends ChunkGenerator> codec() { return ModWorldgen.CHUNK_GENERATOR.get(); }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                         StructureManager structures, ChunkAccess chunk) {
        var plan = (GeneratedAdventurePlan) RuntimePlanRegistry.await(planKey());
        if (hasOcean(chunk, plan)) {
            return oceanDelegate.fillFromNoise(blender, oceanState(plan), structures, chunk)
                    .thenApply(filled -> { composeColumns(filled, plan, randomState, true, structures); return filled; });
        }
        composeColumns(chunk, plan, randomState, false, structures);
        return CompletableFuture.completedFuture(chunk);
    }

    private synchronized RandomState oceanState(GeneratedAdventurePlan plan) {
        if (oceanRandomState == null) oceanRandomState = RandomState.create(oceanSettings, noises, plan.seed());
        return oceanRandomState;
    }

    private boolean hasOcean(ChunkAccess chunk, AdventurePlanView plan) {
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int x = minX; x < minX + 16; x++) for (int z = minZ; z < minZ + 16; z++)
            if (plan.terrainAt(x + 0.5, z + 0.5).waterKind()
                    == WaterKind.OCEAN) return true;
        return false;
    }

    private void composeColumns(ChunkAccess chunk,
                                GeneratedAdventurePlan plan,
                                RandomState randomState, boolean generatedOcean, StructureManager structures) {
        var foundations = new StructureTerrain(structures, chunk.getPos());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int x = minX; x < minX + 16; x++) for (int z = minZ; z < minZ + 16; z++) {
            var terrain = plan.terrainAt(x + .5, z + .5);
            NoiseColumn nativeOcean = null;
            if (generatedOcean && terrain.waterKind() == WaterKind.OCEAN) {
                BlockState[] nativeStates = new BlockState[DEPTH];
                for (int y = MIN_Y; y < MIN_Y + DEPTH; y++)
                    nativeStates[y - MIN_Y] = chunk.getBlockState(pos.set(x, y, z));
                nativeOcean = new NoiseColumn(MIN_Y, nativeStates);
            }
            NoiseColumn column = plannedColumn(plan, x, z, terrain, nativeOcean);
            if (!foundations.isEmpty()) {
                int floor = getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, chunk, randomState);
                int adapted = clamp(foundations.surfaceAt(x, z, floor), MIN_Y + 1, MIN_Y + DEPTH);
                for (int y = Math.min(floor, adapted); y < Math.max(floor, adapted); y++)
                    column.setBlock(y, y < adapted ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            for (int y = MIN_Y; y < MIN_Y + DEPTH; y++)
                chunk.setBlockState(pos.set(x, y, z), column.getBlock(y), false);
            var sample = plan.terrainAt(x + 0.5, z + 0.5);
            if (sample.wet() && sample.waterKind() != WaterKind.OCEAN) {
                int waterTop = (int) StrictMath.floor(sample.waterSurface()) - 1;
                if (waterTop >= MIN_Y && waterTop < MIN_Y + DEPTH
                        && !column.getBlock(waterTop).getFluidState().isEmpty())
                    chunk.markPosForPostprocessing(pos.set(x, waterTop, z));
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG,
                Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState randomState,
                             ChunkAccess chunk) {
        buildPlannedSurface(region.registryAccess(), chunk);
    }

    /** The same surface pass is used in production and chunk-level integration checks. */
    public void buildPlannedSurface(RegistryAccess registries, ChunkAccess chunk) {
        var plan = (GeneratedAdventurePlan) RuntimePlanRegistry.await(planKey());
        // Surface noises use the world seed and our sea-level datum. Rules come from the
        // registered overworld settings, including datapack changes, not adapter palettes.
        RandomState state = oceanState(plan);
        // Surface rules query these columns repeatedly. Snapshot the already composed floor
        // before painting instead of regenerating 384-block columns (including ocean noise).
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        int[] floors = new int[256];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            floors[x * 16 + z] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) + 1;
        var surfaceNoise = new PlannedSurfaceNoiseChunk(chunk, state, oceanSettings,
                (x, z) -> x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16
                        ? floors[(x - minX) * 16 + z - minZ]
                        : getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, chunk, state));
        BiomeManager biomes = new BiomeManager(
                (x, y, z) -> getBiomeSource().getNoiseBiome(x, y, z, state.sampler()),
                BiomeManager.obfuscateSeed(plan.seed()));
        state.surfaceSystem().buildSurface(state, biomes, registries.registryOrThrow(Registries.BIOME),
                false, new WorldGenerationContext(this, chunk), chunk, surfaceNoise, surfaceRule);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG,
                Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
    }

    @Override public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                                       BiomeManager biomeManager, StructureManager structures,
                                       ChunkAccess chunk, GenerationStep.Carving step) {
        var plan = (GeneratedAdventurePlan) RuntimePlanRegistry.await(planKey());
        if (!new StructureTerrain(structures, chunk.getPos()).isEmpty()) return;
        // Ocean caves and aquifers already come from native density generation. Carvers using
        // an unrelated overworld aquifer can drain custom river surfaces, so exclude wet chunks.
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int x = minX - 8; x < minX + 24; x += 4) for (int z = minZ - 8; z < minZ + 24; z += 4)
            if (plan.terrainAt(x + 0.5, z + 0.5).wet()) return;
        vanillaDelegate.applyCarvers(region, seed, randomState, biomeManager, structures, chunk, step);
    }

    @Override public void spawnOriginalMobs(WorldGenRegion region) { vanillaDelegate.spawnOriginalMobs(region); }

    @Override public int getGenDepth() { return DEPTH; }
    @Override public int getSeaLevel() { return SEA_LEVEL; }
    @Override public int getMinY() { return MIN_Y; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level,
                             RandomState randomState) {
        var plan = (GeneratedAdventurePlan) RuntimePlanRegistry.await(planKey());
        MacroSample sample = plan.terrainAt(x + 0.5, z + 0.5);
        int solidTop = clamp(plan.solidSurfaceAt(x, z, sample) - 1, MIN_Y, MIN_Y + DEPTH - 1);
        int waterTop = sample.wet() ? clamp((int) StrictMath.floor(sample.waterSurface()) - 1,
                MIN_Y, MIN_Y + DEPTH - 1) : solidTop;
        return (type.isOpaque().test(Blocks.WATER.defaultBlockState())
                ? StrictMath.max(solidTop, waterTop) : solidTop) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        var plan = (GeneratedAdventurePlan) RuntimePlanRegistry.await(planKey());
        MacroSample sample = plan.terrainAt(x + 0.5, z + 0.5);
        NoiseColumn nativeOcean = sample.waterKind() == WaterKind.OCEAN
                ? oceanDelegate.getBaseColumn(x, z, level, oceanState(plan)) : null;
        return plannedColumn(plan, x, z, sample, nativeOcean);
    }

    private NoiseColumn plannedColumn(GeneratedAdventurePlan plan, int x, int z,
                                      MacroSample sample, NoiseColumn nativeOcean) {
        // The visible floor always comes from the shared plan. Native ocean noise supplies
        // only cavities below a sealed roof, never a second competing surface datum.
        BlockState[] states = new BlockState[DEPTH];
        int solidSurface = plan.solidSurfaceAt(x, z, sample);
        int solidTop = clamp(solidSurface - 1, MIN_Y, MIN_Y + DEPTH - 1);
        int waterTop = sample.wet() ? clamp((int) StrictMath.floor(sample.waterSurface()) - 1,
                MIN_Y, MIN_Y + DEPTH - 1) : solidTop;
        for (int y = MIN_Y; y < MIN_Y + DEPTH; y++) {
            states[y - MIN_Y] = y == MIN_Y ? Blocks.BEDROCK.defaultBlockState()
                    : y <= solidTop ? Blocks.STONE.defaultBlockState()
                    : y <= waterTop ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            // Preserve native underwater caves beneath the planned seabed.
            if (nativeOcean != null && y > MIN_Y && y < solidTop - 8 && !nativeOcean.getBlock(y).blocksMotion())
                states[y - MIN_Y] = nativeOcean.getBlock(y);
        }
        return new NoiseColumn(MIN_Y, states);
    }

    @Override public void addDebugScreenInfo(List<String> lines, RandomState randomState, BlockPos pos) {
        MacroSample sample = RuntimePlanRegistry.await(planKey()).terrainAt(pos.getX(), pos.getZ());
        lines.add("AdventureWorldGen " + sample.terrainVersion());
        lines.add(io.github.luoyan.adventureworldgen.runtime.PlanIdentity.IMPLEMENTATION_REVISION);
        lines.add("Region " + sample.regionId() + " / " + sample.terrainTemplate() + " / " + sample.recipe());
        if(sample.secondaryWeight()>0)lines.add("Composite " + sample.secondaryRecipe() + " @ " + String.format(java.util.Locale.ROOT,"%.2f",sample.secondaryWeight()));
        lines.add(String.format(java.util.Locale.ROOT,"%s slope %.2f relief %.1f range %.2f",sample.landform(),sample.slope(),sample.localRelief(),sample.mountainInfluence()));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return StrictMath.max(minimum, StrictMath.min(maximum, value));
    }
}
