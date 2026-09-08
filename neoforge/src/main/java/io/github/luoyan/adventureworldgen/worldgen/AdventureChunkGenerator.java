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
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
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
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** plan-v2 terrain executor. Its serialized form contains only the fixed profile ID. */
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
    private final io.github.luoyan.adventureworldgen.hydrology.RiverSediments riverSediments =
            new io.github.luoyan.adventureworldgen.hydrology.RiverSediments();
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
    @Override protected MapCodec<? extends ChunkGenerator> codec() { return ModWorldgen.CHUNK_GENERATOR.get(); }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                         StructureManager structures, ChunkAccess chunk) {
        var plan = (GeneratedAdventurePlan) RuntimePlanRegistry.await(profile);
        if (hasOcean(chunk, plan)) {
            return oceanDelegate.fillFromNoise(blender, oceanState(plan), structures, chunk)
                    .thenApply(filled -> { composeColumns(filled, plan, randomState, true); return filled; });
        }
        composeColumns(chunk, plan, randomState, false);
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
                                RandomState randomState, boolean generatedOcean) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int x = minX; x < minX + 16; x++) for (int z = minZ; z < minZ + 16; z++) {
            double blend = oceanBlend(plan, x, z);
            if (generatedOcean && blend >= 1) continue;
            NoiseColumn column = getBaseColumn(x, z, chunk, randomState);
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

    private static double oceanBlend(GeneratedAdventurePlan plan, int x, int z) {
        return io.github.luoyan.adventureworldgen.terrain.IslandMacroTerrain.oceanBlend(
                -plan.coastline().signedDistance(x + 0.5, z + 0.5),plan.seaBand());
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState randomState,
                             ChunkAccess chunk) {
        buildPlannedSurface(region.registryAccess(), chunk);
    }

    /** The same surface pass is used in production and chunk-level integration checks. */
    public void buildPlannedSurface(RegistryAccess registries, ChunkAccess chunk) {
        var plan = (GeneratedAdventurePlan) RuntimePlanRegistry.await(profile);
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
                BiomeManager.obfuscateSeed(plan.seed())) {
            @Override public Holder<Biome> getBiome(BlockPos pos) {
                // Preserve vanilla's fuzzy Voronoi zoom from quart palettes to block columns.
                // Directly reading pos >> 2 exposes the palette as large square stair steps.
                Holder<Biome> biome = super.getBiome(pos);
                // 1.21.1 SurfaceSystem probes the air above the column to grow badlands
                // pillars before applying rules. Disable that terrain extension only;
                // rule evaluations inside the column still see ERODED_BADLANDS.
                if (biome.is(Biomes.ERODED_BADLANDS) && pos.getY() > chunk.getHeight(
                        Heightmap.Types.WORLD_SURFACE_WG, pos.getX() & 15, pos.getZ() & 15))
                    return registries.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.BADLANDS);
                return biome;
            }
        };
        state.surfaceSystem().buildSurface(state, biomes, registries.registryOrThrow(Registries.BIOME),
                false, new WorldGenerationContext(this, chunk), chunk, surfaceNoise, surfaceRule);
        buildRiverbeds(registries, chunk, plan);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG,
                Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
    }

    private void buildRiverbeds(RegistryAccess registries, ChunkAccess chunk, GeneratedAdventurePlan plan) {
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int localX = 0; localX < 16; localX++) for (int localZ = 0; localZ < 16; localZ++) {
            int x = minX + localX, z = minZ + localZ;
            var sample = plan.terrainAt(x + 0.5, z + 0.5);
            if (sample.waterKind() != WaterKind.RIVER && sample.waterKind() != WaterKind.LAKE) continue;
            int bedY = plan.solidSurfaceAt(x, z, sample) - 1;
            if (bedY <= MIN_Y || bedY + 1 >= MIN_Y + DEPTH
                    || !chunk.getBlockState(position.set(x, bedY + 1, z)).is(Blocks.WATER)) continue;
            var palette = riverSediments.surface(sample, plan.seed(), x, z);
            Block top = registries.registryOrThrow(Registries.BLOCK)
                    .get(ResourceLocation.parse(palette.top().value()));
            Block under = registries.registryOrThrow(Registries.BLOCK)
                    .get(ResourceLocation.parse(palette.under().value()));
            if (top == null || under == null) throw new IllegalStateException("biome adapter returned an unregistered block");
            if (!chunk.getBlockState(position.set(x, bedY, z)).blocksMotion()) continue;
            chunk.setBlockState(position, top.defaultBlockState(), false);
            for (int depth = 1; depth <= palette.underDepth() && bedY - depth > MIN_Y; depth++) {
                position.set(x, bedY - depth, z);
                if (!chunk.getBlockState(position).blocksMotion()) break;
                chunk.setBlockState(position, under.defaultBlockState(), false);
            }
        }
    }

    @Override public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                                       BiomeManager biomeManager, StructureManager structures,
                                       ChunkAccess chunk, GenerationStep.Carving step) {
        var plan = (GeneratedAdventurePlan) RuntimePlanRegistry.await(profile);
        // Ocean caves and aquifers already come from native density generation. Carvers using
        // an unrelated overworld aquifer can drain custom river surfaces, so exclude wet chunks.
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int x = minX - 8; x < minX + 24; x += 4) for (int z = minZ - 8; z < minZ + 24; z += 4)
            if (plan.terrainAt(x + 0.5, z + 0.5).wet()) return;
        vanillaDelegate.applyCarvers(region, seed, randomState, biomeManager, structures, chunk, step);
    }

    @Override public void spawnOriginalMobs(WorldGenRegion region) { vanillaDelegate.spawnOriginalMobs(region); }

    @Override
    public void createStructures(RegistryAccess registries, ChunkGeneratorStructureState state,
                                 StructureManager manager, ChunkAccess chunk, StructureTemplateManager templates) {
        // Generate non-controlled vanilla structures normally, then erase the controlled native candidate before publish.
        super.createStructures(registries, state, manager, chunk, templates);
        AdventurePlanView plan = RuntimePlanRegistry.await(profile);
        var structureRegistry = registries.registryOrThrow(Registries.STRUCTURE);
        for (var controlledId : plan.controlledStructureIds()) {
            Structure controlled = structureRegistry.get(ResourceLocation.parse(controlledId.value()));
            if (controlled == null) throw new IllegalStateException("controlled structure is missing: " + controlledId);
            if (chunk.getAllStarts().containsKey(controlled))
                chunk.setStartForStructure(controlled, StructureStart.INVALID_START);
        }

        ChunkPos chunkPos = chunk.getPos();
        for (var planned : plan.structuresIntersecting(chunkPos.x, chunkPos.z)) {
            if (Math.floorDiv(planned.originX(), 16) != chunkPos.x
                    || Math.floorDiv(planned.originZ(), 16) != chunkPos.z) continue;
            Structure controlled = structureRegistry.get(ResourceLocation.parse(planned.structureId().value()));
            if (controlled == null) throw new IllegalStateException("planned structure is missing: " + planned.structureId());
            java.util.ArrayList<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces = new java.util.ArrayList<>();
            for (var frozen : planned.pieces()) {
                try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(frozen.canonicalNbt()))) {
                    var tag = NbtIo.read(input);
                    String pieceType = tag.getString("id");
                    if (!pieceType.equals("minecraft:tedp")) throw new IllegalStateException(
                            "unsupported frozen piece type " + pieceType + " for " + planned.instanceId());
                    pieces.add(new DesertPyramidPiece(tag));
                } catch (IOException failure) {
                    throw new IllegalStateException("could not restore frozen piece " + frozen.pieceId(), failure);
                }
            }
            chunk.setStartForStructure(controlled,
                    new StructureStart(controlled, chunkPos, 0, new PiecesContainer(pieces)));
        }
    }
    @Override public int getGenDepth() { return DEPTH; }
    @Override public int getSeaLevel() { return SEA_LEVEL; }
    @Override public int getMinY() { return MIN_Y; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level,
                             RandomState randomState) {
        var plan = (GeneratedAdventurePlan) RuntimePlanRegistry.await(profile);
        if (oceanBlend(plan, x, z) <= 0) {
            MacroSample sample = plan.terrainAt(x + 0.5, z + 0.5);
            int solidTop = clamp(plan.solidSurfaceAt(x, z, sample) - 1, MIN_Y, MIN_Y + DEPTH - 1);
            int waterTop = sample.wet() ? clamp((int) StrictMath.floor(sample.waterSurface()) - 1,
                    MIN_Y, MIN_Y + DEPTH - 1) : solidTop;
            return (type.isOpaque().test(Blocks.WATER.defaultBlockState())
                    ? StrictMath.max(solidTop, waterTop) : solidTop) + 1;
        }
        NoiseColumn column = getBaseColumn(x, z, level, randomState);
        for (int y = MIN_Y + DEPTH - 1; y >= MIN_Y; y--)
            if (type.isOpaque().test(column.getBlock(y))) return y + 1;
        return MIN_Y;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        var plan = (GeneratedAdventurePlan) RuntimePlanRegistry.await(profile);
        MacroSample sample = plan.terrainAt(x + 0.5, z + 0.5);
        double blend = oceanBlend(plan, x, z);
        NoiseColumn nativeOcean = blend > 0 ? oceanDelegate.getBaseColumn(x, z, level, oceanState(plan)) : null;
        if (blend >= 1) return nativeOcean;
        double ground = sample.groundSurface();
        if (nativeOcean != null) {
            int nativeFloor = MIN_Y;
            for (int y = SEA_LEVEL - 1; y >= MIN_Y; y--) {
                if (nativeOcean.getBlock(y).blocksMotion()) { nativeFloor = y + 1; break; }
            }
            // Both endpoints use the same sea-level datum; only the ocean bed is blended.
            ground = plan.seaSurface() + (nativeFloor - plan.seaSurface()) * blend
                    - plan.oceanCarvingAt(x + 0.5, z + 0.5);
        }
        BlockState[] states = new BlockState[DEPTH];
        int solidSurface = nativeOcean == null ? plan.solidSurfaceAt(x, z, sample) : (int) StrictMath.floor(ground);
        int solidTop = clamp(solidSurface - 1, MIN_Y, MIN_Y + DEPTH - 1);
        int waterTop = sample.wet() ? clamp((int) StrictMath.floor(sample.waterSurface()) - 1,
                MIN_Y, MIN_Y + DEPTH - 1) : solidTop;
        for (int y = MIN_Y; y < MIN_Y + DEPTH; y++) {
            states[y - MIN_Y] = y == MIN_Y ? Blocks.BEDROCK.defaultBlockState()
                    : y <= solidTop ? Blocks.STONE.defaultBlockState()
                    : y <= waterTop ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            // Preserve native underwater caves beneath the unmodified seabed.
            if (nativeOcean != null && y < solidTop - 8 && !nativeOcean.getBlock(y).blocksMotion())
                states[y - MIN_Y] = nativeOcean.getBlock(y);
        }
        return new NoiseColumn(MIN_Y, states);
    }

    @Override public void addDebugScreenInfo(List<String> lines, RandomState randomState, BlockPos pos) {
        MacroSample sample = RuntimePlanRegistry.await(profile).terrainAt(pos.getX(), pos.getZ());
        lines.add("AdventureWorldGen " + sample.terrainVersion());
        lines.add(io.github.luoyan.adventureworldgen.runtime.RuntimePlanner.IMPLEMENTATION_REVISION);
        lines.add("Region " + sample.regionId() + " / " + sample.terrainTemplate() + " / " + sample.recipe());
        if(sample.secondaryWeight()>0)lines.add("Composite " + sample.secondaryRecipe() + " @ " + String.format(java.util.Locale.ROOT,"%.2f",sample.secondaryWeight()));
        lines.add(String.format(java.util.Locale.ROOT,"%s slope %.2f relief %.1f range %.2f",sample.landform(),sample.slope(),sample.localRelief(),sample.mountainInfluence()));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return StrictMath.max(minimum, StrictMath.min(maximum, value));
    }
}
