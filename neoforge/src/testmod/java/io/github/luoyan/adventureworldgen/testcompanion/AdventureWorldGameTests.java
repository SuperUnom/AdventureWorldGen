package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.config.LoadedProfile;
import io.github.luoyan.adventureworldgen.worldgen.ProfileReloadListener;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanner;
import io.github.luoyan.adventureworldgen.worldgen.MinecraftAdapters;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.plan.PlanningStage;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry;
import io.github.luoyan.adventureworldgen.worldgen.AdventureChunkGenerator;
import io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules;

@GameTestHolder("testcompanion")
@PrefixGameTestTemplate(false)
public final class AdventureWorldGameTests {
    private static final String EMPTY = "bastion/mobs/empty";
    private static volatile GeneratedAdventurePlan planned;
    private AdventureWorldGameTests() {}

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 1200)
    public static void companionProfilePlansSafeSpawn(GameTestHelper helper) {
        var plan = plan();
        var spawn = plan.spawnPosition();
        var terrain = plan.terrainAt(spawn.x(), spawn.z());
        helper.assertTrue(!terrain.wet() && !terrain.hazardous(), "planned spawn is not safe dry terrain");
        helper.assertTrue(plan.biomeAt((int) spawn.x(), (int) spawn.y(), (int) spawn.z())
                .equals(new ContentId("minecraft:plains")), "planned spawn biome is not plains");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 1200)
    public static void externalBiomeAdapterAndResourcesRemainActive(GameTestHelper helper) {
        var biomeRegistry = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var biome = biomeRegistry.get(ResourceLocation.parse(TestCompanionAdapters.ASHEN_GROVE_ID.value()));
        helper.assertTrue(biome != null, "test companion biome was not loaded from datapack resources");
        helper.assertTrue(biome.getGenerationSettings().features().size() > 9
                        && biome.getGenerationSettings().features().get(9).size() > 0,
                "test companion biome has no vegetation feature step");
        helper.assertTrue(MinecraftAdapters.builtIn().biome(TestCompanionAdapters.ASHEN_GROVE_ID)
                        == TestCompanionAdapters.ASHEN_GROVE,
                "external biome compatibility adapter was not selected");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 1200)
    public static void plannedOceanColumnsPreserveDeepCavesAndContinuousWater(GameTestHelper helper) {
        var plan = plan();
        var id = ResourceLocation.fromNamespaceAndPath("adventureworldgen", "ocean_regression");
        io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry.start(new ContentId(id.toString()), () -> plan).join();
        var registries = helper.getLevel().registryAccess();
        var generator = new io.github.luoyan.adventureworldgen.worldgen.AdventureChunkGenerator(id,
                registries.lookupOrThrow(Registries.BIOME), registries.lookupOrThrow(Registries.NOISE_SETTINGS),
                registries.lookupOrThrow(Registries.NOISE));
        var settings = registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(
                net.minecraft.resources.ResourceKey.create(Registries.NOISE_SETTINGS,
                        ResourceLocation.fromNamespaceAndPath("adventureworldgen", "ocean")));
        var random = net.minecraft.world.level.levelgen.RandomState.create(settings.value(),
                registries.lookupOrThrow(Registries.NOISE), plan.seed());
        var nativeGenerator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(generator.getBiomeSource(), settings);
        var level = helper.getLevel();
        // Isolated NOISE-stage chunks have no structure references. Do not ask the live world
        // to load these distant chunks from a worker while its test tick waits for completion.
        var noStructures = new net.minecraft.world.level.StructureManager(level,
                new net.minecraft.world.level.levelgen.WorldOptions(plan.seed(), false, false), null) {
            @Override public java.util.List<net.minecraft.world.level.levelgen.structure.StructureStart> startsForStructure(
                    net.minecraft.world.level.ChunkPos pos,
                    java.util.function.Predicate<net.minecraft.world.level.levelgen.structure.Structure> predicate) {
                return java.util.List.of();
            }
        };
        for (int[] coordinate : new int[][]{{4096, 4096}, {-4096, 4096}, {-4096, -4096}, {4096, -4096}}) {
            int x = coordinate[0], z = coordinate[1];
            var pos = new net.minecraft.world.level.ChunkPos(x >> 4, z >> 4);
            var actual = new net.minecraft.world.level.chunk.ProtoChunk(pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                    level, registries.registryOrThrow(Registries.BIOME), null);
            var expected = new net.minecraft.world.level.chunk.ProtoChunk(pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                    level, registries.registryOrThrow(Registries.BIOME), null);
            var actualFuture = generator.fillFromNoise(net.minecraft.world.level.levelgen.blending.Blender.empty(), random,
                    noStructures, actual);
            level.getServer().managedBlock(actualFuture::isDone);
            actualFuture.join();
            var expectedFuture = nativeGenerator.fillFromNoise(net.minecraft.world.level.levelgen.blending.Blender.empty(), random,
                    noStructures, expected);
            level.getServer().managedBlock(expectedFuture::isDone);
            expectedFuture.join();
            for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
                var column = generator.getBaseColumn(x + dx, z + dz, level, random);
                helper.assertTrue(column.getBlock(63).is(net.minecraft.world.level.block.Blocks.WATER), "ocean water surface has a hole");
                helper.assertTrue(column.getBlock(64).isAir(), "ocean water datum is inconsistent");
                for (int y = -64; y < 320; y++) {
                    var block = new net.minecraft.core.BlockPos(x + dx, y, z + dz);
                    int floor=plan.solidSurfaceAt(x+dx,z+dz,plan.terrainAt(x+dx+.5,z+dz+.5));
                    var nativeBlock=expected.getBlockState(block);
                    var wanted=y==-64?net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState()
                            :y<floor?net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
                            :y<64?net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()
                            :net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                    if(y>-64 && y<floor-9 && !nativeBlock.blocksMotion())wanted=nativeBlock;
                    helper.assertTrue(actual.getBlockState(block).equals(wanted),
                            "planned ocean floor/cave composition differs at " + block);
                }
            }
        }
        for (var point : plan.coastline().equalArcSamples(128, 16384)) {
            double angle = StrictMath.atan2(point.z(), point.x());
            for (int offset = -24; offset <= 24; offset++) {
                int x = (int) StrictMath.floor(point.x() + StrictMath.cos(angle) * offset);
                int z = (int) StrictMath.floor(point.z() + StrictMath.sin(angle) * offset);
                var sample = plan.terrainAt(x + 0.5, z + 0.5);
                var column = generator.getBaseColumn(x, z, level, random);
                if (sample.waterKind() == io.github.luoyan.adventureworldgen.api.WaterKind.OCEAN)
                    helper.assertTrue(!column.getBlock(63).isAir(), "dry hole at coast");
                else if (!sample.wet()) helper.assertTrue(sample.groundSurface() >= 64, "dry land below sea level");
            }
        }
        helper.succeed();
    }

    private static void assertTerrainBiomes(GameTestHelper helper, GeneratedAdventurePlan plan,
            io.github.luoyan.adventureworldgen.config.AdventureWorldConfig config) {
        helper.assertTrue(config.biomes().required().size() == 23, "default woodland/mountain requirements disappeared");
        var supply = new java.util.HashMap<String, Long>();
        for (var entry : plan.climate().supply()) supply.put(entry.biome(), entry.climateArea());
        for (var required : config.biomes().required()) {
            var patch = plan.biomePatches().stream().filter(p -> p.patchId().equals(required.patchId())).findFirst().orElse(null);
            if (patch == null) {
                // r29: a demand with no legal position records zero supply and may be absent, so a
                // missing patch is no longer a failure by itself. It must still agree with the plan's
                // own supply record - a biome with legal climate area cannot simply disappear.
                helper.assertTrue(supply.getOrDefault(required.id().value(), 0L) == 0,
                        "required biome disappeared although the plan recorded legal climate area: " + required.id());
                continue;
            }
            long area = 0;
            for (int z = patch.minZ() + 2; z < patch.maxZExclusive(); z += 4)
                for (int x = patch.minX() + 2; x < patch.maxXExclusive(); x += 4) if (patch.contains(x,z)) {
                    helper.assertTrue(plan.landBiomeAt(x,z).equals(required.id()), "required biome lost actual ownership: " + required.id());
                    helper.assertTrue(config.biomes().allows(required.id(),plan.terrainAt(x,z)), "required biome spills onto forbidden terrain");
                    area += 16;
                }
            // r29 accepts the achieved area when legal supply cannot reach the request, and keeps the
            // configured maximum as a hard ceiling. Its own final check is the comparison below:
            // mixing may not drop a patch below the smaller of the request and the achieved area.
            helper.assertTrue(area <= required.area().max(), "required area exceeds its configured maximum: " + required.id());
            helper.assertTrue(plan.effectiveArea(patch) >= Math.min(required.area().min(), patch.area()),
                    "mixing or water consumed the achieved area: " + required.id());
            long owned = 0;
            for (long body : componentAreas(patch)) owned += body;
            // One demand may cover several legal regions whose areas add up, so a single body is not
            // required. What must still hold: the ownership set is exactly what the loop above walked.
            helper.assertTrue(owned == area, "recorded ownership does not match its components: " + required.id());
        }
        helper.assertTrue(config.biomes().filler().stream().filter(id->id.value().contains("windswept")).count()==4,"new windswept filler candidates disappeared");
        helper.assertTrue(config.biomes().filler().stream().filter(id->id.value().contains("badlands")).count()==3,"new badlands filler candidates disappeared");
        int mountains = 0, openHotLand = 0;
        var environmentRules = new io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules(config, plan.climate());
        for (int z = -3000; z < 3000; z += 32) for (int x = -3000; x < 3000; x += 32) {
            var terrain = plan.terrainAt(x+2,z+2);
            if (terrain.waterKind() == io.github.luoyan.adventureworldgen.api.WaterKind.OCEAN) continue;
            var biome = plan.landBiomeAt(x,z);
            helper.assertTrue(config.biomes().allows(biome,terrain), "filler violates terrain rule: " + biome + " at " + x + "," + z);
            if(terrain.waterKind()==io.github.luoyan.adventureworldgen.api.WaterKind.NONE) {
                // Temperature preferences can fall back across bands. Native snowfall does not
                // override author configuration; frozen moisture and shore rules remain mandatory.
                helper.assertTrue(environmentRules.allows(biome,x+2,z+2,terrain),
                        "biome violates frozen environment: "+biome+" at "+x+","+z);
            }
            if (terrain.terrainTemplate().equals("mountains")) mountains++;
            if (biome.value().equals("minecraft:desert")||biome.value().equals("minecraft:savanna")) openHotLand++;
        }
        helper.assertTrue(mountains > 100 && openHotLand > 50, "terrain-rule test lacks mountain/open hot land coverage");
    }

    /**
     * Areas of the disconnected ownership bodies of one patch, largest first.
     *
     * <p>Ownership is a sparse set of quart cells that are 4x4 blocks each; neighbours are the four
     * 4-block steps and diagonals do not connect. r29 allows one demand to cover several legal
     * regions whose areas add up, so callers must treat a multi-body patch as normal and only
     * require that the bodies account for the whole recorded area.
     */
    private static long[] componentAreas(PlannedBiomePatch patch) {
        var remaining=new java.util.HashSet<Long>();
        for(long cell:patch.mask().cells())remaining.add(cell);
        var areas=new java.util.ArrayList<Long>();
        while(!remaining.isEmpty()) {
            long first=remaining.iterator().next();remaining.remove(first);
            var queue=new java.util.ArrayDeque<Long>();queue.add(first);long area=0;
            while(!queue.isEmpty()) {
                long cell=queue.removeFirst();area+=16;
                int x=io.github.luoyan.adventureworldgen.spatial.CellMask.x(cell),z=io.github.luoyan.adventureworldgen.spatial.CellMask.z(cell);
                for(int[] d:new int[][]{{4,0},{-4,0},{0,4},{0,-4}}) {
                    long next=io.github.luoyan.adventureworldgen.spatial.CellMask.key(x+d[0],z+d[1]);
                    if(remaining.remove(next))queue.add(next);
                }
            }
            areas.add(area);
        }
        areas.sort(java.util.Comparator.reverseOrder());
        long[] result=new long[areas.size()];
        for(int i=0;i<result.length;i++)result[i]=areas.get(i);
        return result;
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 2400)
    public static void crashSeedPlansEveryRequiredBiome(GameTestHelper helper) {
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(
                "../src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json"))) {
            var config = new io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser().parse(reader);
            String canonical = io.github.luoyan.adventureworldgen.config.CanonicalConfigJson.write(config);
            var loaded = new LoadedProfile(ProfileReloadListener.DEFAULT_ID, config, canonical, "crash-seed-r11",
                    io.github.luoyan.adventureworldgen.worldgen.StructureRoadInformation.load(helper.getLevel().getServer().getResourceManager(), config));
            var generated = RuntimePlanner.plan(4126649097427443736L, loaded, java.nio.file.Path.of("crash-seed-r11"), MinecraftAdapters.builtIn());
            assertTerrainBiomes(helper, generated, config);
            helper.assertTrue(!generated.roads().routes().isEmpty(), "production roads are empty for this seed");
            var reloaded=RuntimePlanner.plan(4126649097427443736L,loaded,java.nio.file.Path.of("crash-seed-r11"),MinecraftAdapters.builtIn());
            helper.assertTrue(generated.roads().equals(reloaded.roads()), "READY changed frozen roads");
            var progress=io.github.luoyan.adventureworldgen.runtime.PlanningProgress.current();
            helper.assertTrue(progress.status()==io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Status.READY
                    && progress.stage()==PlanningStage.CACHE,
                    "READY reload reran filling or transition");
            for(int z=-3000;z<3000;z+=71)for(int x=-3000;x<3000;x+=71)
                helper.assertTrue(generated.biomeAt(x,64,z).equals(reloaded.biomeAt(x,64,z)),"reload changed biome ownership");
            helper.assertTrue(!generated.terrainAt(generated.spawnPosition().x(),generated.spawnPosition().z()).wet(),"crash seed has wet spawn");
            helper.assertTrue(generated.structures().size()==config.structures().size(),"production village count differs from configured count");
            for(var placement:generated.structures()) helper.assertTrue(generated.roads().nodes().stream().anyMatch(
                    n->n.id().equals("structure/"+placement.instanceId())&&n.required()),"village missing required road node: "+placement.instanceId());
            helper.assertTrue(generated.roads().equals(reloaded.roads()),"READY changed village road connections");
            RoadGameTests.assertPlannedVillageStarts(helper,generated);
            helper.succeed();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 2400)
    public static void constrainedCoastStillPlansAllRequiredBiomes(GameTestHelper helper) {
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(
                "../src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json"))) {
            var config = new io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser().parse(reader);
            String canonical = io.github.luoyan.adventureworldgen.config.CanonicalConfigJson.write(config);
            var loaded = new LoadedProfile(ProfileReloadListener.DEFAULT_ID, config, canonical, "capacity-seed-r12",
                    io.github.luoyan.adventureworldgen.worldgen.StructureRoadInformation.load(helper.getLevel().getServer().getResourceManager(), config));
            var generated = RuntimePlanner.plan(1, loaded, java.nio.file.Path.of("capacity-seed-r12"), MinecraftAdapters.builtIn());
            assertTerrainBiomes(helper, generated, config);
            helper.assertTrue(Math.abs(java.util.Arrays.stream(generated.climate().actualRatios()).sum()-1)<1e-9, "climate land ratios do not sum to one");
            helper.assertTrue(generated.biomePatches().stream().filter(p -> p.mask()!=null).allMatch(p -> p.contains(p.anchorX(),p.anchorZ())), "frozen ownership lost a required anchor");
            helper.succeed();
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 2400)
    public static void productionProfilePlansIrregularContinentAtRadius3000(GameTestHelper helper) {
        productionProfilePlansAndReloads(helper,7331,"production-profile-r6");
    }

    static void productionProfilePlansAndReloads(GameTestHelper helper,long seed,String directory) {
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(
                "../src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json"))) {
            var config = new io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser().parse(reader);
            helper.assertTrue(config.world().radius() == 3000, "production radius is not 3000");
            String canonical = io.github.luoyan.adventureworldgen.config.CanonicalConfigJson.write(config);
            var loaded = new LoadedProfile(ProfileReloadListener.DEFAULT_ID, config, canonical, "production-profile-test",
                    io.github.luoyan.adventureworldgen.worldgen.StructureRoadInformation.load(helper.getLevel().getServer().getResourceManager(), config));
            var generated = RuntimePlanner.plan(seed, loaded, java.nio.file.Path.of(directory), MinecraftAdapters.builtIn());
            assertTerrainBiomes(helper, generated, config);
            var reloaded=RuntimePlanner.plan(seed,loaded,java.nio.file.Path.of(directory),MinecraftAdapters.builtIn());
            var progress=io.github.luoyan.adventureworldgen.runtime.PlanningProgress.current();
            helper.assertTrue(progress.status()==io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Status.READY
                    && progress.stage()==PlanningStage.CACHE,
                    "READY reload reran filling or transition");
            for(int z=-3000;z<3000;z+=71)for(int x=-3000;x<3000;x+=71)
                helper.assertTrue(generated.biomeAt(x,64,z).equals(reloaded.biomeAt(x,64,z)),"reload changed biome ownership");
            helper.assertTrue(!generated.terrainAt(generated.spawnPosition().x(), generated.spawnPosition().z()).wet(), "wet production spawn");
            helper.assertTrue(generated.structures().size()==config.structures().size(),"production village count differs from configured count");
            for(var placement:generated.structures()) helper.assertTrue(generated.roads().nodes().stream().anyMatch(
                    n->n.id().equals("structure/"+placement.instanceId())&&n.required()),"village missing required road node: "+placement.instanceId());
            helper.assertTrue(generated.roads().equals(reloaded.roads()),"READY changed village road connections");
            RoadGameTests.assertPlannedVillageStarts(helper,generated);
            helper.assertTrue(generated.coastline().vertices().stream().allMatch(point -> StrictMath.hypot(point.x(), point.z()) <= 3000.001),
                    "continent exceeds configured extent");
            helper.succeed();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 2400)
    public static void reportedSeedHasContainedDescendingRivers(GameTestHelper helper) {
        long seed = 345705185492107788L;
        GeneratedAdventurePlan generated;
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(
                "../src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json"))) {
            var config = new io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser().parse(reader);
            String canonical = io.github.luoyan.adventureworldgen.config.CanonicalConfigJson.write(config);
            var loaded = new LoadedProfile(ProfileReloadListener.DEFAULT_ID, config, canonical, "reported-river-seed",
                    io.github.luoyan.adventureworldgen.worldgen.StructureRoadInformation.load(helper.getLevel().getServer().getResourceManager(), config));
            generated = RuntimePlanner.plan(seed, loaded, java.nio.file.Path.of("reported-river-seed-r7"), MinecraftAdapters.builtIn());
            assertTerrainBiomes(helper, generated, config);
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        var id = ResourceLocation.fromNamespaceAndPath("adventureworldgen", "river_regression");
        io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry.start(new ContentId(id.toString()), () -> generated).join();
        var level = helper.getLevel();
        var registries = level.registryAccess();
        var generator = new io.github.luoyan.adventureworldgen.worldgen.AdventureChunkGenerator(id,
                registries.lookupOrThrow(Registries.BIOME), registries.lookupOrThrow(Registries.NOISE_SETTINGS),
                registries.lookupOrThrow(Registries.NOISE));
        var random = level.getChunkSource().randomState();
        assertSurfaceFollowsBiome(helper,generated,generator);
        java.util.Set<net.minecraft.world.level.ChunkPos> positions = new java.util.LinkedHashSet<>();
        positions.add(new net.minecraft.world.level.ChunkPos(new net.minecraft.core.BlockPos(-416, 64, -810)));
        for (var river : generated.riverNetwork().channels()) {
            for (double along : new double[]{0.25, 0.5, 0.75}) {
                var point = river.points().get((int) (along * (river.points().size() - 1)));
                if (generated.coastline().signedDistance(point.x(), point.z()) > 128)
                    positions.add(new net.minecraft.world.level.ChunkPos(new net.minecraft.core.BlockPos(
                            (int) StrictMath.floor(point.x()), 64, (int) StrictMath.floor(point.z()))));
            }
        }
        int waterColumns = 0, descendingEdges = 0, waterBiomeColumns = 0;
        int protectedBeds = 0;
        for (var pos : positions) {
            var chunk = new net.minecraft.world.level.chunk.ProtoChunk(pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                    level, registries.registryOrThrow(Registries.BIOME), null);
            generator.createBiomes(random, net.minecraft.world.level.levelgen.blending.Blender.empty(),
                    level.structureManager(), chunk).join();
            chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
            var future = generator.fillFromNoise(net.minecraft.world.level.levelgen.blending.Blender.empty(), random,
                    level.structureManager(), chunk);
            level.getServer().managedBlock(future::isDone); future.join();
            // Native surfacing must preserve third-party blocks on submerged river/lake beds.
            var bedMarkers = new java.util.ArrayList<net.minecraft.core.BlockPos>();
            for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
                int x = pos.getMinBlockX() + dx, z = pos.getMinBlockZ() + dz;
                var sample = generated.terrainAt(x + .5, z + .5);
                if (sample.waterKind() != io.github.luoyan.adventureworldgen.api.WaterKind.RIVER
                        && sample.waterKind() != io.github.luoyan.adventureworldgen.api.WaterKind.LAKE) continue;
                var bed = new net.minecraft.core.BlockPos(x, generated.solidSurfaceAt(x, z, sample) - 1, z);
                if (!chunk.getBlockState(bed.above()).is(net.minecraft.world.level.block.Blocks.WATER)) continue;
                chunk.setBlockState(bed, net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
                bedMarkers.add(bed);
            }
            generator.buildPlannedSurface(registries, chunk);
            for (var bed : bedMarkers)
                helper.assertTrue(chunk.getBlockState(bed).is(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK),
                        "surface overwrote a third-party bed block at " + bed);
            protectedBeds += bedMarkers.size();
            java.util.Set<net.minecraft.core.BlockPos> updates = new java.util.HashSet<>();
            var processing = chunk.getPostProcessing();
            for (int section = 0; section < processing.length; section++) if (processing[section] != null)
                for (short packed : processing[section]) updates.add(net.minecraft.world.level.chunk.ProtoChunk.unpackOffsetCoordinates(
                        packed, chunk.getSectionYFromSectionIndex(section), pos));
            // Include the halo so block-level containment is also checked across chunk boundaries.
            var columns = new net.minecraft.world.level.NoiseColumn[18][18];
            for (int dx = -1; dx <= 16; dx++) for (int dz = -1; dz <= 16; dz++)
                columns[dx + 1][dz + 1] = generator.getBaseColumn(pos.getMinBlockX() + dx, pos.getMinBlockZ() + dz, level, random);
            for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
                int x = pos.getMinBlockX() + dx, z = pos.getMinBlockZ() + dz;
                var sample = generated.terrainAt(x + 0.5, z + 0.5);
                if (!sample.wet()) continue;
                int top = (int) StrictMath.floor(sample.waterSurface()) - 1;
                var surface = new net.minecraft.core.BlockPos(x, top, z);
                if (chunk.getBlockState(surface).getFluidState().isEmpty()) continue;
                waterColumns++;
                var storedBiome = chunk.getNoiseBiome(x >> 2, top >> 2, z >> 2);
                var expectedBiome = ResourceLocation.parse(generated.biomeAt(x, top, z).value());
                helper.assertTrue(storedBiome.is(net.minecraft.resources.ResourceKey.create(Registries.BIOME, expectedBiome)),
                        "stored biome palette disagrees with surface at " + surface);
                if (storedBiome.is(net.minecraft.world.level.biome.Biomes.RIVER)
                        || storedBiome.is(net.minecraft.world.level.biome.Biomes.FROZEN_RIVER)) waterBiomeColumns++;
                helper.assertTrue(updates.contains(surface), "river source omitted fluid postprocessing at " + surface);
                for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    var other = columns[dx + step[0] + 1][dz + step[1] + 1];
                    if (other.getBlock(top).isAir()) {
                        helper.assertTrue(!other.getBlock(top - 1).getFluidState().isEmpty(),
                                "water is suspended above a dry bank or drops more than one block at " + surface);
                        descendingEdges++;
                    }
                }
            }
        }
        helper.assertTrue(protectedBeds > 100, "regression did not exercise submerged bed preservation");
        helper.assertTrue(waterBiomeColumns > 100, "river water has no actual river biome palette");
        helper.assertTrue(io.github.luoyan.adventureworldgen.runtime.PlanningProgress.current().status()
                        == io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Status.READY,
                "progress did not finish after publishing the plan");
        helper.assertTrue(waterColumns > 100, "regression did not generate enough river columns");
        helper.assertTrue(descendingEdges > 0, "regression did not exercise a descending water step");
        helper.assertTrue(generated.riverNetwork().channels().stream().filter(channel -> channel.lake() != null).count() <= 2,
                "reported seed still contains excessive large lakes");
        helper.assertTrue(generated.riverNetwork().wetlands().size() <= 1, "reported seed contains excessive wetlands");
        helper.succeed();
    }

    private static GeneratedAdventurePlan plan() {
        GeneratedAdventurePlan current = planned;
        if (current != null) return current;
        synchronized (AdventureWorldGameTests.class) {
            if (planned == null) {
                planned = RuntimePlanner.plan(0x41D0_2026_0907L, ProfileReloadListener.current(),
                        java.nio.file.Path.of("testcompanion-plan"), MinecraftAdapters.builtIn());
            }
            return planned;
        }
    }


    private static void assertSurfaceFollowsBiome(GameTestHelper helper, GeneratedAdventurePlan plan,
                                                  AdventureChunkGenerator generator) {
        var level = helper.getLevel();
        var random = level.getChunkSource().randomState();
        int verified = 0;
        for (int z = -1536; z <= 1536; z += 512) for (int x = -1536; x <= 1536; x += 512) {
            boolean ocean = false;
            for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++)
                ocean |= plan.terrainAt(x + dx + .5, z + dz + .5).waterKind()
                        == io.github.luoyan.adventureworldgen.api.WaterKind.OCEAN;
            // Detached chunks have no WorldGenRegion for the native ocean beardifier.
            // This check exercises land; ocean/coast columns have a separate regression.
            if (ocean) continue;
            var pos = new net.minecraft.world.level.ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            var chunk = new net.minecraft.world.level.chunk.ProtoChunk(pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                    level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
            generator.createBiomes(random, net.minecraft.world.level.levelgen.blending.Blender.empty(),
                    level.structureManager(), chunk).join();
            chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
            var fill = generator.fillFromNoise(net.minecraft.world.level.levelgen.blending.Blender.empty(),
                    random, level.structureManager(), chunk);
            level.getServer().managedBlock(fill::isDone); fill.join();
            generator.buildPlannedSurface(level.registryAccess(), chunk);
            for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                int wx = x + dx, wz = z + dz;
                var sample = plan.terrainAt(wx + .5, wz + .5);
                if (sample.wet()) continue;
                var id = plan.biomeAt(wx, 64, wz);
                helper.assertTrue(id.equals(plan.surfaceBiomeAt(wx, wz)), "surface performed an independent biome mix");
                helper.assertTrue(chunk.getNoiseBiome(wx >> 2, 16, wz >> 2).is(ResourceLocation.parse(id.value())),
                        "surface changed stored biome ownership");
                helper.assertTrue(chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, dx, dz)
                        == plan.solidSurfaceAt(wx, wz, sample) - 1, "surface changed planned land height");
                verified++;
            }
        }
        helper.assertTrue(verified > 256, "surface test did not sample enough dry land");
    }
}
