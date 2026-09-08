package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.config.ContentId;
import io.github.luoyan.adventureworldgen.config.ProfileManager;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanner;
import io.github.luoyan.adventureworldgen.worldgen.MinecraftAdapters;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
    public static void planContainsAllFourBiomesAndFrozenPyramid(GameTestHelper helper) {
        var plan = plan();
        boolean pyramid = false;
        java.util.Set<ContentId> biomes = new java.util.HashSet<>();
        for (int x = -1536; x <= 1536; x += 4) for (int z = -1536; z <= 1536; z += 4)
            biomes.add(plan.biomeAt(x, 64, z));
        for (int cx = -96; cx <= 96; cx++) for (int cz = -96; cz <= 96; cz++)
            pyramid |= plan.structuresIntersecting(cx, cz).stream().anyMatch(structure ->
                    structure.structureId().equals(new ContentId("minecraft:desert_pyramid"))
                            && structure.pieces().stream().allMatch(piece -> piece.canonicalNbt().length > 0));
        helper.assertTrue(biomes.containsAll(java.util.Set.of(new ContentId("minecraft:plains"),
                new ContentId("minecraft:forest"), new ContentId("minecraft:desert"),
                new ContentId("minecraft:snowy_plains"), TestCompanionAdapters.ASHEN_GROVE_ID)),
                "plan does not expose all required vanilla and companion biomes");
        helper.assertTrue(pyramid, "plan does not contain a frozen desert pyramid");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 1200)
    public static void externalAdaptersPreserveSurfaceVegetationLootAndChunkIdempotency(GameTestHelper helper) {
        var biomeRegistry = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var biome = biomeRegistry.get(ResourceLocation.parse(TestCompanionAdapters.ASHEN_GROVE_ID.value()));
        helper.assertTrue(biome != null, "test companion biome was not loaded from datapack resources");
        helper.assertTrue(biome.getGenerationSettings().features().size() > 9
                        && biome.getGenerationSettings().features().get(9).size() > 0,
                "test companion biome has no vegetation feature step");
        var surface = MinecraftAdapters.builtIn().biome(TestCompanionAdapters.ASHEN_GROVE_ID)
                .surface(plan().terrainAt(0, 0));
        helper.assertTrue(surface.top().equals(new ContentId("minecraft:podzol")),
                "external biome adapter surface was not selected");

        var frozen = plan().structures().stream().filter(item ->
                item.structureId().equals(TestCompanionAdapters.WAYSTATION_ID)).findFirst().orElseThrow();
        helper.assertTrue(frozen.pieces().size() == 2, "waystation did not freeze both pieces");
        try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(
                frozen.pieces().getFirst().canonicalNbt()))) {
            var nbt = NbtIo.read(input);
            helper.assertTrue(nbt.getString("LootTable").equals("minecraft:chests/simple_dungeon"),
                    "waystation loot table was not frozen into piece NBT");
        } catch (java.io.IOException failure) {
            throw new AssertionError("could not decode frozen waystation NBT", failure);
        }

        var adapter = MinecraftAdapters.builtIn().structure(TestCompanionAdapters.WAYSTATION_ID).orElseThrow();
        var candidate = new io.github.luoyan.adventureworldgen.api.StructureAdapter.Candidate(frozen.instanceId(),
                frozen.originX(), frozen.originY(), frozen.originZ(), frozen.rotation());
        var prepared = new io.github.luoyan.adventureworldgen.api.StructureAdapter.Prepared(candidate, frozen.pieces(),
                frozen.footprint(), frozen.biomeProtection(), frozen.entranceX(), frozen.entranceY(), frozen.entranceZ());
        java.util.Set<String> commits = new java.util.HashSet<>();
        java.util.List<String> placements = new java.util.ArrayList<>();
        var target = new io.github.luoyan.adventureworldgen.api.StructureAdapter.PlacementTarget() {
            @Override public boolean beginOnce(String instanceId, String pieceId, int chunkX, int chunkZ) {
                return commits.add(instanceId + "/" + pieceId + "/" + chunkX + "/" + chunkZ);
            }
            @Override public void placeCanonicalPiece(io.github.luoyan.adventureworldgen.api.AdventurePlanView.PlannedPiece piece) {
                placements.add(piece.pieceId());
            }
        };
        java.util.Set<Long> chunks = new java.util.HashSet<>();
        for (var piece : frozen.pieces()) for (int cx = Math.floorDiv(piece.minX(), 16); cx <= Math.floorDiv(piece.maxX(), 16); cx++)
            for (int cz = Math.floorDiv(piece.minZ(), 16); cz <= Math.floorDiv(piece.maxZ(), 16); cz++)
                chunks.add((((long) cx) << 32) ^ (cz & 0xffff_ffffL));
        for (long key : chunks) {
            int cx = (int) (key >> 32), cz = (int) key;
            adapter.placeChunk(prepared, cx, cz, target);
            adapter.placeChunk(prepared, cx, cz, target);
        }
        helper.assertTrue(chunks.size() > 1 && placements.size() == commits.size(),
                "cross-chunk waystation placement was not idempotent");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 1200)
    public static void nativeOceanColumnsAndCoastHaveContinuousWater(GameTestHelper helper) {
        var plan = plan();
        var id = ResourceLocation.fromNamespaceAndPath("adventureworldgen", "ocean_regression");
        io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry.start(id, () -> plan).join();
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
                    helper.assertTrue(actual.getBlockState(block).equals(expected.getBlockState(block)),
                            "offshore chunk differs from native ocean density at " + block);
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
        for (var required : config.biomes().required()) {
            var patch = plan.biomePatches().stream().filter(p -> p.patchId().equals(required.patchId())).findFirst().orElseThrow();
            long area = 0;
            for (int z = patch.minZ() + 2; z < patch.maxZExclusive(); z += 4)
                for (int x = patch.minX() + 2; x < patch.maxXExclusive(); x += 4) if (patch.contains(x,z)) {
                    helper.assertTrue(plan.landBiomeAt(x,z).equals(required.id()), "required biome lost actual ownership: " + required.id());
                    helper.assertTrue(config.biomes().allows(required.id(),plan.terrainAt(x,z)), "required biome spills onto forbidden terrain");
                    area += 16;
                }
            helper.assertTrue(area >= required.area().min() && area <= required.area().max(), "required area is incorrect");
            helper.assertTrue(plan.effectiveArea(patch)>=required.area().min(),"mixing or water consumed minimum effective area");
            helper.assertTrue(largestComponentArea(patch) == area,
                    "required biome lost its main contiguous body: " + required.id());
        }
        helper.assertTrue(config.biomes().filler().stream().noneMatch(id->id.value().contains("windswept")),"windswept remains in filler pool");
        int mountains = 0, openHotLand = 0;
        for (int z = -3000; z < 3000; z += 32) for (int x = -3000; x < 3000; x += 32) {
            var terrain = plan.terrainAt(x+2,z+2);
            if (terrain.waterKind() == io.github.luoyan.adventureworldgen.api.WaterKind.OCEAN) continue;
            var biome = plan.landBiomeAt(x,z);
            helper.assertTrue(config.biomes().allows(biome,terrain), "filler violates terrain rule: " + biome + " at " + x + "," + z);
            if(terrain.waterKind()==io.github.luoyan.adventureworldgen.api.WaterKind.NONE) {
                var nativeBiome=helper.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                        .get(net.minecraft.resources.ResourceLocation.parse(biome.value()));
                var position=new net.minecraft.core.BlockPos(x+2,(int)Math.ceil(terrain.groundSurface())+1,z+2);
                var type=plan.climate().typeAt(x+2,z+2,terrain);
                if(type==io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.TemperatureType.VERY_COLD)
                    helper.assertTrue(nativeBiome.hasPrecipitation()&&nativeBiome.coldEnoughToSnow(position),"very cold land has no native snow: "+biome);
                if(type==io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.TemperatureType.COLD)
                    helper.assertTrue(!nativeBiome.coldEnoughToSnow(position),"cold land unexpectedly snows: "+biome+" at "+position);
            }
            if (terrain.terrainTemplate().equals("mountains")) mountains++;
            if (biome.value().equals("minecraft:desert")||biome.value().equals("minecraft:savanna")) openHotLand++;
        }
        helper.assertTrue(mountains > 100 && openHotLand > 50, "terrain-rule test lacks mountain/open hot land coverage");
    }

    private static long largestComponentArea(GeneratedAdventurePlan.PlannedBiomePatch patch) {
        var remaining=new java.util.HashSet<Long>();
        for(long cell:patch.mask().cells())remaining.add(cell);
        long largest=0;
        while(!remaining.isEmpty()) {
            long first=remaining.iterator().next();remaining.remove(first);
            var queue=new java.util.ArrayDeque<Long>();queue.add(first);long area=0;
            while(!queue.isEmpty()) {
                long cell=queue.removeFirst();area+=16;
                int x=io.github.luoyan.adventureworldgen.planner.CellMask.x(cell),z=io.github.luoyan.adventureworldgen.planner.CellMask.z(cell);
                for(int[] d:new int[][]{{4,0},{-4,0},{0,4},{0,-4}}) {
                    long next=io.github.luoyan.adventureworldgen.planner.CellMask.key(x+d[0],z+d[1]);
                    if(remaining.remove(next))queue.add(next);
                }
            }
            largest=Math.max(largest,area);
        }
        return largest;
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 2400)
    public static void crashSeedPlansEveryRequiredBiome(GameTestHelper helper) {
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(
                "../src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json"))) {
            var config = new io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser().parse(reader);
            String canonical = io.github.luoyan.adventureworldgen.config.CanonicalConfigJson.write(config);
            var loaded = new ProfileManager.LoadedProfile(ProfileManager.DEFAULT_ID, config, canonical,
                    io.github.luoyan.adventureworldgen.persistence.AtomicPlanRepository.sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "crash-seed-r11");
            var generated = RuntimePlanner.plan(4126649097427443736L, loaded, java.nio.file.Path.of("crash-seed-r11"), MinecraftAdapters.builtIn());
            assertTerrainBiomes(helper, generated, config);
            var reloaded=RuntimePlanner.plan(4126649097427443736L,loaded,java.nio.file.Path.of("crash-seed-r11"),MinecraftAdapters.builtIn());
            var progress=io.github.luoyan.adventureworldgen.runtime.PlanningProgress.current();
            helper.assertTrue(progress.status()==io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Status.READY
                    && progress.stage()==io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Stage.CACHE,
                    "READY reload reran filling or transition");
            for(int z=-3000;z<3000;z+=71)for(int x=-3000;x<3000;x+=71)
                helper.assertTrue(generated.biomeAt(x,64,z).equals(reloaded.biomeAt(x,64,z)),"reload changed biome ownership");
            helper.assertTrue(!generated.terrainAt(generated.spawnPosition().x(),generated.spawnPosition().z()).wet(),"crash seed has wet spawn");
            helper.assertTrue(generated.structures().size() == 1,"crash seed lost its required pyramid");
            helper.succeed();
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 2400)
    public static void constrainedCoastStillPlansAllRequiredBiomes(GameTestHelper helper) {
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(
                "../src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json"))) {
            var config = new io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser().parse(reader);
            String canonical = io.github.luoyan.adventureworldgen.config.CanonicalConfigJson.write(config);
            var loaded = new ProfileManager.LoadedProfile(ProfileManager.DEFAULT_ID, config, canonical,
                    io.github.luoyan.adventureworldgen.persistence.AtomicPlanRepository.sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "capacity-seed-r12");
            var generated = RuntimePlanner.plan(1, loaded, java.nio.file.Path.of("capacity-seed-r12"), MinecraftAdapters.builtIn());
            assertTerrainBiomes(helper, generated, config);
            helper.assertTrue(Math.abs(java.util.Arrays.stream(generated.climate().actualRatios()).sum()-1)<1e-9, "climate land ratios do not sum to one");
            helper.assertTrue(generated.biomePatches().stream().filter(p -> p.mask()!=null).allMatch(p -> p.contains(p.anchorX(),p.anchorZ())), "frozen ownership lost a required anchor");
            helper.succeed();
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 2400)
    public static void productionProfilePlansIrregularContinentAtRadius3000(GameTestHelper helper) {
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(
                "../src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json"))) {
            var config = new io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser().parse(reader);
            helper.assertTrue(config.world().radius() == 3000, "production radius is not 3000");
            String canonical = io.github.luoyan.adventureworldgen.config.CanonicalConfigJson.write(config);
            var loaded = new ProfileManager.LoadedProfile(ProfileManager.DEFAULT_ID, config, canonical,
                    io.github.luoyan.adventureworldgen.persistence.AtomicPlanRepository.sha256(
                            canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "production-profile-test");
            var generated = RuntimePlanner.plan(7331, loaded, java.nio.file.Path.of("production-profile-r6"), MinecraftAdapters.builtIn());
            assertTerrainBiomes(helper, generated, config);
            var reloaded=RuntimePlanner.plan(7331,loaded,java.nio.file.Path.of("production-profile-r6"),MinecraftAdapters.builtIn());
            var progress=io.github.luoyan.adventureworldgen.runtime.PlanningProgress.current();
            helper.assertTrue(progress.status()==io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Status.READY
                    && progress.stage()==io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Stage.CACHE,
                    "READY reload reran filling or transition");
            for(int z=-3000;z<3000;z+=71)for(int x=-3000;x<3000;x+=71)
                helper.assertTrue(generated.biomeAt(x,64,z).equals(reloaded.biomeAt(x,64,z)),"reload changed biome ownership");
            helper.assertTrue(!generated.terrainAt(generated.spawnPosition().x(), generated.spawnPosition().z()).wet(), "wet production spawn");
            helper.assertTrue(generated.structures().stream().anyMatch(structure -> structure.structureId().value().equals("minecraft:desert_pyramid")),
                    "production profile failed to place pyramid");
            helper.assertTrue(generated.coastline().vertices().stream().allMatch(point -> StrictMath.hypot(point.x(), point.z()) <= 3000.001),
                    "continent exceeds configured extent");
            helper.succeed();
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 2400)
    public static void reportedSeedHasContainedDescendingRivers(GameTestHelper helper) {
        long seed = 345705185492107788L;
        GeneratedAdventurePlan generated;
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(
                "../src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json"))) {
            var config = new io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser().parse(reader);
            String canonical = io.github.luoyan.adventureworldgen.config.CanonicalConfigJson.write(config);
            var loaded = new ProfileManager.LoadedProfile(ProfileManager.DEFAULT_ID, config, canonical,
                    io.github.luoyan.adventureworldgen.persistence.AtomicPlanRepository.sha256(
                            canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "reported-river-seed");
            generated = RuntimePlanner.plan(seed, loaded, java.nio.file.Path.of("reported-river-seed-r7"), MinecraftAdapters.builtIn());
            assertTerrainBiomes(helper, generated, config);
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        var id = ResourceLocation.fromNamespaceAndPath("adventureworldgen", "river_regression");
        io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry.start(id, () -> generated).join();
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
        java.util.Set<net.minecraft.world.level.block.Block> riverbedMaterials = new java.util.HashSet<>();
        for (var pos : positions) {
            var chunk = new net.minecraft.world.level.chunk.ProtoChunk(pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                    level, registries.registryOrThrow(Registries.BIOME), null);
            generator.createBiomes(random, net.minecraft.world.level.levelgen.blending.Blender.empty(),
                    level.structureManager(), chunk).join();
            chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
            var future = generator.fillFromNoise(net.minecraft.world.level.levelgen.blending.Blender.empty(), random,
                    level.structureManager(), chunk);
            level.getServer().managedBlock(future::isDone); future.join();
            generator.buildLandSurface(registries, chunk);
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
                if (sample.waterKind() == io.github.luoyan.adventureworldgen.api.WaterKind.RIVER) {
                    for (int bedY = top - 1; bedY > level.getMinBuildHeight(); bedY--) {
                        var bed = chunk.getBlockState(new net.minecraft.core.BlockPos(x, bedY, z));
                        if (bed.blocksMotion()) {
                            riverbedMaterials.add(bed.getBlock());
                            helper.assertTrue(!bed.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK), "underwater riverbed has grass at " + x + "," + z + " biome=" + generated.surfaceBiomeAt(x, z));
                            break;
                        }
                    }
                }
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
        helper.assertTrue(riverbedMaterials.size() >= 3, "generated riverbed still uses a uniform material");
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
                planned = RuntimePlanner.plan(0x41D0_2026_0907L, ProfileManager.current(),
                        java.nio.file.Path.of("testcompanion-plan"), MinecraftAdapters.builtIn());
            }
            return planned;
        }
    }

    private static void assertSurfaceFollowsBiome(GameTestHelper helper,GeneratedAdventurePlan plan,
            io.github.luoyan.adventureworldgen.worldgen.AdventureChunkGenerator generator) {
        var level=helper.getLevel();int verified=0;
        for(int z=-1536;z<=1536;z+=512)for(int x=-1536;x<=1536;x+=512) {
            var pos=new net.minecraft.world.level.ChunkPos(Math.floorDiv(x,16),Math.floorDiv(z,16));
            var chunk=new net.minecraft.world.level.chunk.ProtoChunk(pos,net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                    level,level.registryAccess().registryOrThrow(Registries.BIOME),null);
            for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++)chunk.setBlockState(
                    new net.minecraft.core.BlockPos(x+dx,80,z+dz),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),false);
            generator.buildLandSurface(level.registryAccess(),chunk);
            for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++) {
                int wx=x+dx,wz=z+dz;var sample=plan.terrainAt(wx+.5,wz+.5);if(sample.wet())continue;
                var id=plan.biomeAt(wx,64,wz);
                helper.assertTrue(id.equals(plan.surfaceBiomeAt(wx,wz)),"surface performed an independent biome mix");
                var expected=MinecraftAdapters.builtIn().biome(id).surface(sample).top();
                var block=level.registryAccess().registryOrThrow(Registries.BLOCK).get(ResourceLocation.parse(expected.value()));
                helper.assertTrue(chunk.getBlockState(new net.minecraft.core.BlockPos(wx,80,wz)).is(block),"surface did not follow queried biome");
                verified++;
            }
        }
        helper.assertTrue(verified>256,"surface test did not sample enough dry land");
    }
}
