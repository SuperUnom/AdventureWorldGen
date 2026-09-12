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
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.plan.PlanningStage;
import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.FrozenPieceSupport;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.StructureAdapter;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.config.CanonicalConfigJson;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry;
import io.github.luoyan.adventureworldgen.worldgen.GenericBiomeAdapter;
import io.github.luoyan.adventureworldgen.worldgen.RegisteredPieceSupport;
import io.github.luoyan.adventureworldgen.worldgen.AdventureChunkGenerator;
import io.github.luoyan.adventureworldgen.worldgen.FrozenPieceRestore;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
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
    public static void frozenPiecesRestoreThroughRegisteredPieceTypes(GameTestHelper helper) {
        var level = helper.getLevel();
        var context = FrozenPieceRestore.context(level.registryAccess(), level.getStructureManager());
        var planned = pyramidOf(plan());
        var restored = FrozenPieceRestore.restore(planned, context);
        helper.assertTrue(restored.size() == planned.pieces().size(),
                "frozen piece restore dropped pieces instead of rebuilding every one");
        for (int index = 0; index < restored.size(); index++) {
            var frozen = planned.pieces().get(index);
            var piece = restored.get(index);
            helper.assertTrue(piece.getType() == StructurePieceType.DESERT_PYRAMID_PIECE,
                    "restored piece did not come back as its registered type");
            assertFrozenBox(helper, frozen, piece.getBoundingBox(), "restored piece " + frozen.pieceId());
            // Re-serializing has to reproduce what was frozen: the rotation, the generator depth and
            // the frozen chest decisions all survive the round trip through the piece type.
            helper.assertTrue(piece.createTag(context).equals(frozenTag(frozen)),
                    "restored piece " + frozen.pieceId() + " does not re-serialize to the frozen NBT");
        }

        var unknown = frozenPiece("testcompanion:not_a_registered_piece", planned.instanceId() + "/piece/unknown");
        IllegalStateException rejection = null;
        try {
            FrozenPieceRestore.restore(new AdventurePlanView.PlannedStructure(planned.instanceId(),
                    planned.structureId(), planned.originX(), planned.originY(), planned.originZ(),
                    planned.rotation(), java.util.List.of(unknown)), context);
        } catch (IllegalStateException expected) {
            rejection = expected;
        }
        helper.assertTrue(rejection != null, "an unregistered frozen piece type was accepted instead of rejected");
        helper.assertTrue(rejection.getMessage().contains("testcompanion:not_a_registered_piece")
                        && rejection.getMessage().contains(planned.instanceId()),
                "the unsupported piece diagnostic does not name the type and the instance: " + rejection.getMessage());
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 1200)
    public static void plannedStartsInjectThroughRegisteredPieceTypes(GameTestHelper helper) {
        var plan = plan();
        var level = helper.getLevel();
        var registries = level.registryAccess();
        var id = ResourceLocation.fromNamespaceAndPath("adventureworldgen", "structure_injection");
        RuntimePlanRegistry.start(new ContentId(id.toString()), () -> plan).join();
        var generator = new AdventureChunkGenerator(id, registries.lookupOrThrow(Registries.BIOME),
                registries.lookupOrThrow(Registries.NOISE_SETTINGS), registries.lookupOrThrow(Registries.NOISE));
        // This test is about the planned injection, so the vanilla pass gets no structure set to
        // assemble: only the shared generator's own start injection runs here.
        var state = ChunkGeneratorStructureState.createForNormal(level.getChunkSource().randomState(),
                plan.seed(), generator.getBiomeSource(), noStructureSets());
        // The subject is the companion mod's own piece type: the generator restores it without
        // naming it, which is what "a new structure is a registration, not a branch" has to mean.
        var planned = waystationOf(plan);
        var waystation = registries.registryOrThrow(Registries.STRUCTURE)
                .get(ResourceLocation.parse(planned.structureId().value()));
        helper.assertTrue(waystation != null, "the controlled structure is missing from the registry");

        var origin = new ChunkPos(Math.floorDiv(planned.originX(), 16), Math.floorDiv(planned.originZ(), 16));
        var chunk = protoChunk(level, registries, origin);
        generator.createStructures(registries, state, level.structureManager(), chunk, level.getStructureManager());
        var injected = chunk.getStartForStructure(waystation);
        helper.assertTrue(injected != null && injected.isValid(),
                "the planned structure was not injected into its origin chunk");
        helper.assertTrue(injected.getPieces().size() == planned.pieces().size(),
                "the injected start does not carry every frozen piece");
        for (int index = 0; index < injected.getPieces().size(); index++) {
            var frozen = planned.pieces().get(index);
            var piece = injected.getPieces().get(index);
            helper.assertTrue(piece instanceof WaystationPiece,
                    "injected piece did not come back as the registered companion type: " + piece.getClass().getName());
            assertFrozenBox(helper, frozen, piece.getBoundingBox(), "injected piece " + frozen.pieceId());
        }

        // A neighbour chunk only overlaps the footprint. The plan injects a start at the origin
        // chunk alone, and a native candidate under the controlled id is erased wherever it appears.
        var neighbourPos = new ChunkPos(origin.x + 1, origin.z);
        var neighbour = protoChunk(level, registries, neighbourPos);
        neighbour.setStartForStructure(waystation, new StructureStart(waystation, neighbourPos, 0,
                new PiecesContainer(java.util.List.of(injected.getPieces().getFirst()))));
        generator.createStructures(registries, state, level.structureManager(), neighbour, level.getStructureManager());
        var erased = neighbour.getStartForStructure(waystation);
        helper.assertTrue(erased == null || !erased.isValid(),
                "a controlled native candidate survived the planned structure pass");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 2400)
    public static void waystationRestoresAsCompanionPieceAfterReadyReload(GameTestHelper helper) {
        var generated = plan();
        // Same seed, profile, adapters and directory: this second call takes the READY path, so it
        // compares a fresh planning result against a decode of the frozen bytes, not two plannings.
        var reloaded = RuntimePlanner.plan(0x41D0_2026_0907L, ProfileReloadListener.current(),
                java.nio.file.Path.of("testcompanion-plan"), MinecraftAdapters.builtIn(),
                RegisteredPieceSupport.INSTANCE);
        var progress = io.github.luoyan.adventureworldgen.runtime.PlanningProgress.current();
        helper.assertTrue(progress.status() == io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Status.READY
                        && progress.stage() == PlanningStage.CACHE,
                "the second call replanned instead of restoring the frozen plan");

        var before = waystationOf(generated);
        var after = waystationOf(reloaded);
        helper.assertTrue(before.rotation().equals(after.rotation()),
                "READY reload changed the structure rotation");
        helper.assertTrue(before.entranceX() == after.entranceX() && before.entranceY() == after.entranceY()
                        && before.entranceZ() == after.entranceZ(),
                "READY reload changed the entrance");
        helper.assertTrue(before.footprint().equals(after.footprint())
                        && before.biomeProtection().equals(after.biomeProtection()),
                "READY reload changed the footprint or the biome protection");
        helper.assertTrue(before.pieces().size() == after.pieces().size(),
                "READY reload changed the frozen piece count");
        for (int index = 0; index < before.pieces().size(); index++)
            helper.assertTrue(before.pieces().get(index).equals(after.pieces().get(index)),
                    "READY reload changed frozen piece " + index + " or its order");

        var level = helper.getLevel();
        var context = FrozenPieceRestore.context(level.registryAccess(), level.getStructureManager());
        var restored = FrozenPieceRestore.restore(after, context);
        helper.assertTrue(restored.size() == after.pieces().size(),
                "companion pieces did not all come back");
        for (int index = 0; index < restored.size(); index++) {
            var frozen = after.pieces().get(index);
            var piece = restored.get(index);
            helper.assertTrue(piece instanceof WaystationPiece,
                    "frozen companion piece restored as " + piece.getClass().getName());
            var waystation = (WaystationPiece) piece;
            helper.assertTrue(waystation.index() == index,
                    "restored companion piece lost its frozen index");
            assertFrozenBox(helper, frozen, waystation.getBoundingBox(), "restored " + frozen.pieceId());
            // Rotation, index, variant, loot table and loot seed all live in the frozen NBT, so an
            // identical re-serialization is the whole round trip in one comparison.
            helper.assertTrue(waystation.createTag(context).equals(frozenTag(frozen)),
                    "restored companion piece does not re-serialize to the frozen NBT");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 2400)
    public static void coldPlanningRejectsUnregisteredFrozenPiece(GameTestHelper helper) {
        var directory = java.nio.file.Path.of(PIECE_CHECK_DIRECTORY);
        var failure = planWithPoisonedPiece(directory, RegisteredPieceSupport.INSTANCE);
        assertPieceRestoreFailure(helper, failure);
        helper.assertTrue(!java.nio.file.Files.exists(readyFile(directory)),
                "a plan whose pieces cannot be restored still published READY");
        restoreSharedProgress();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 2400)
    public static void readyDecodeRejectsUnregisteredFrozenPiece(GameTestHelper helper) {
        var directory = java.nio.file.Path.of(PIECE_CHECK_READY_DIRECTORY);
        // First pass accepts any piece type, so a READY plan exists and the decode-time check can be
        // isolated. The support answer is a runtime property: it is not part of the input identity.
        var accepted = planWithPoisonedPiece(directory, structure -> java.util.Optional.empty());
        helper.assertTrue(accepted == null,
                "the permissive pass unexpectedly failed: " + (accepted == null ? "" : accepted.getMessage()));
        helper.assertTrue(java.nio.file.Files.exists(readyFile(directory)),
                "the permissive pass did not publish a READY plan");
        var failure = planWithPoisonedPiece(directory, RegisteredPieceSupport.INSTANCE);
        assertPieceRestoreFailure(helper, failure);
        helper.assertTrue(java.nio.file.Files.exists(readyFile(directory)),
                "the failed decode consumed or rewrote the READY plan");
        restoreSharedProgress();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 1200)
    public static void externalAdaptersPreserveCompatibilityVegetationAndFrozenLoot(GameTestHelper helper) {
        var biomeRegistry = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var biome = biomeRegistry.get(ResourceLocation.parse(TestCompanionAdapters.ASHEN_GROVE_ID.value()));
        helper.assertTrue(biome != null, "test companion biome was not loaded from datapack resources");
        helper.assertTrue(biome.getGenerationSettings().features().size() > 9
                        && biome.getGenerationSettings().features().get(9).size() > 0,
                "test companion biome has no vegetation feature step");
        helper.assertTrue(MinecraftAdapters.builtIn().biome(TestCompanionAdapters.ASHEN_GROVE_ID)
                        == TestCompanionAdapters.ASHEN_GROVE,
                "external biome compatibility adapter was not selected");

        var frozen = plan().structures().stream().filter(item ->
                item.structureId().equals(TestCompanionAdapters.WAYSTATION_ID)).findFirst().orElseThrow();
        helper.assertTrue(frozen.pieces().size() == 2, "waystation did not freeze both pieces");
        java.util.Set<Long> chunks = new java.util.HashSet<>();
        for (var piece : frozen.pieces()) {
            try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(piece.canonicalNbt()))) {
                var nbt = NbtIo.read(input);
                helper.assertTrue(nbt.getString("LootTable").equals("minecraft:chests/simple_dungeon"),
                        "waystation loot table was not frozen into piece NBT");
            } catch (java.io.IOException failure) {
                throw new AssertionError("could not decode frozen waystation NBT", failure);
            }
            for (int cx = Math.floorDiv(piece.minX(), 16); cx <= Math.floorDiv(piece.maxX(), 16); cx++)
                for (int cz = Math.floorDiv(piece.minZ(), 16); cz <= Math.floorDiv(piece.maxZ(), 16); cz++)
                    chunks.add((((long) cx) << 32) ^ (cz & 0xffff_ffffL));
        }
        // The plan carries the footprint; placement is Minecraft's own pipeline, so a piece that
        // spans chunks is expected to be frozen once and referenced by every chunk it covers.
        helper.assertTrue(chunks.size() > 1, "waystation pieces no longer cross a chunk boundary");
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
            var loaded = new LoadedProfile(ProfileReloadListener.DEFAULT_ID, config, canonical, "crash-seed-r11");
            var generated = RuntimePlanner.plan(4126649097427443736L, loaded, java.nio.file.Path.of("crash-seed-r11"), MinecraftAdapters.builtIn(), RegisteredPieceSupport.INSTANCE);
            assertTerrainBiomes(helper, generated, config);
            var reloaded=RuntimePlanner.plan(4126649097427443736L,loaded,java.nio.file.Path.of("crash-seed-r11"),MinecraftAdapters.builtIn(), RegisteredPieceSupport.INSTANCE);
            var progress=io.github.luoyan.adventureworldgen.runtime.PlanningProgress.current();
            helper.assertTrue(progress.status()==io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Status.READY
                    && progress.stage()==PlanningStage.CACHE,
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
            var loaded = new LoadedProfile(ProfileReloadListener.DEFAULT_ID, config, canonical, "capacity-seed-r12");
            var generated = RuntimePlanner.plan(1, loaded, java.nio.file.Path.of("capacity-seed-r12"), MinecraftAdapters.builtIn(), RegisteredPieceSupport.INSTANCE);
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
            var loaded = new LoadedProfile(ProfileReloadListener.DEFAULT_ID, config, canonical, "production-profile-test");
            var generated = RuntimePlanner.plan(seed, loaded, java.nio.file.Path.of(directory), MinecraftAdapters.builtIn(), RegisteredPieceSupport.INSTANCE);
            assertTerrainBiomes(helper, generated, config);
            var reloaded=RuntimePlanner.plan(seed,loaded,java.nio.file.Path.of(directory),MinecraftAdapters.builtIn(), RegisteredPieceSupport.INSTANCE);
            var progress=io.github.luoyan.adventureworldgen.runtime.PlanningProgress.current();
            helper.assertTrue(progress.status()==io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Status.READY
                    && progress.stage()==PlanningStage.CACHE,
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
            var loaded = new LoadedProfile(ProfileReloadListener.DEFAULT_ID, config, canonical, "reported-river-seed");
            generated = RuntimePlanner.plan(seed, loaded, java.nio.file.Path.of("reported-river-seed-r7"), MinecraftAdapters.builtIn(), RegisteredPieceSupport.INSTANCE);
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
                        java.nio.file.Path.of("testcompanion-plan"), MinecraftAdapters.builtIn(),
                        RegisteredPieceSupport.INSTANCE);
            }
            return planned;
        }
    }

    /** The frozen pyramid the companion profile requires, in plan order. */
    private static AdventurePlanView.PlannedStructure pyramidOf(GeneratedAdventurePlan plan) {
        return plan.structures().stream().filter(structure ->
                        structure.structureId().equals(new ContentId("minecraft:desert_pyramid")))
                .findFirst().orElseThrow();
    }

    /** The companion waystation the profile requires, in plan order. */
    private static AdventurePlanView.PlannedStructure waystationOf(GeneratedAdventurePlan plan) {
        return plan.structures().stream().filter(structure ->
                        structure.structureId().equals(TestCompanionAdapters.WAYSTATION_ID))
                .findFirst().orElseThrow();
    }

    private static final String PIECE_CHECK_PROFILE = "testcompanion:piece_check";
    private static final String POISONED_PIECE_TYPE = "testcompanion:not_a_registered_piece";
    private static final String PIECE_CHECK_DIRECTORY = "testcompanion-piece-check-cold";
    private static final String PIECE_CHECK_READY_DIRECTORY = "testcompanion-piece-check-ready";

    /**
     * Plans a throwaway profile whose only structure freezes {@link #POISONED_PIECE_TYPE}, which no
     * environment registers. Returns the failure instead of throwing so a test can assert on it.
     */
    private static PlanningFailure planWithPoisonedPiece(java.nio.file.Path directory,
                                                         FrozenPieceSupport pieceSupport) {
        var config = new AdventureWorldConfigParser().parse("""
                {"world":{"radius":512},"spawn":{"biome":"minecraft:plains"},
                 "biomes":{"required":[{"id":"minecraft:plains","adventure_level":1,
                                        "area":{"min":1024,"max":4096}}],
                           "filler":["minecraft:plains"]},
                 "structures":[{"id":"testcompanion:waystation","adventure_level":1,
                                "count":{"min":1,"max":1},
                                "allowed_biomes":{"id":["minecraft:plains"],"area":{"min":1024,"max":4096}},
                                "placement_mode":"scattered","entrance":[0,1,-7]}]}
                """);
        String canonical = CanonicalConfigJson.write(config);
        var loaded = new LoadedProfile(new ContentId(PIECE_CHECK_PROFILE), config, canonical, "piece-check-r1");
        var adapters = AdapterRegistry.builder(new GenericBiomeAdapter()).add(POISONED_WAYSTATION).build();
        try {
            RuntimePlanner.plan(0x5EED_0001L, loaded, directory, adapters, pieceSupport);
            return null;
        } catch (PlanningFailure expected) {
            return expected;
        }
    }

    private static void assertPieceRestoreFailure(GameTestHelper helper, PlanningFailure failure) {
        helper.assertTrue(failure != null,
                "planning accepted a frozen piece type that no environment registers");
        helper.assertTrue(failure.code() == PlanningFailure.Code.UNSUPPORTED_CONTENT,
                "wrong failure code for an unrestorable frozen piece: " + failure.code());
        helper.assertTrue(failure.stage().equals("structure-piece-restore"),
                "wrong failure stage for an unrestorable frozen piece: " + failure.stage());
        var diagnostics = failure.diagnostics();
        helper.assertTrue(TestCompanionAdapters.WAYSTATION_ID.value().equals(diagnostics.get("structure_id")),
                "the failure does not name the structure: " + diagnostics);
        helper.assertTrue(diagnostics.get("instance_id") != null && diagnostics.get("piece_id") != null,
                "the failure does not name the instance and the piece: " + diagnostics);
        helper.assertTrue(POISONED_PIECE_TYPE.equals(diagnostics.get("piece_type")),
                "the failure does not name the frozen piece type: " + diagnostics);
    }

    /** {@code testcompanion:piece_check} plan directory, matching AtomicPlanRepository's layout. */
    private static java.nio.file.Path readyFile(java.nio.file.Path directory) {
        return directory.resolve("adventureworldgen").resolve("plans")
                .resolve(PIECE_CHECK_PROFILE.replace(':', '_').replace('/', '_')).resolve("READY");
    }

    /**
     * PlanningProgress is a process-wide display hook and these tests deliberately fail a plan, so
     * they finish by taking the READY path once more. That leaves the shared hook in the completed
     * state the other tests assert on.
     */
    private static void restoreSharedProgress() {
        RuntimePlanner.plan(0x41D0_2026_0907L, ProfileReloadListener.current(),
                java.nio.file.Path.of("testcompanion-plan"), MinecraftAdapters.builtIn(),
                RegisteredPieceSupport.INSTANCE);
    }

    /** An adapter that freezes a piece type no environment registers, for the early-diagnostic tests. */
    private static final StructureAdapter POISONED_WAYSTATION = new StructureAdapter() {
        @Override public ContentId structureId() { return TestCompanionAdapters.WAYSTATION_ID; }
        @Override public String adapterVersion() { return "testcompanion-waystation-poisoned-v1"; }
        @Override public Descriptor describe() {
            return new Descriptor(java.util.List.of("north"), 16.0, true, true);
        }
        @Override public Prepared prepare(Candidate candidate, long structureSeed) {
            var tag = new CompoundTag();
            tag.putString("id", POISONED_PIECE_TYPE);
            tag.putInt("GD", 0);
            try (var bytes = new java.io.ByteArrayOutputStream();
                 var output = new java.io.DataOutputStream(bytes)) {
                NbtIo.write(tag, output);
                output.flush();
                var piece = new AdventurePlanView.PlannedPiece(candidate.instanceId() + "/piece/0",
                        candidate.originX() - 4, candidate.originY(), candidate.originZ() - 4,
                        candidate.originX() + 4, candidate.originY() + 4, candidate.originZ() + 4,
                        bytes.toByteArray());
                var box = new StructureAdapter.HorizontalBox(piece.minX(), piece.minZ(), piece.maxX(), piece.maxZ());
                return new Prepared(candidate, java.util.List.of(piece), java.util.List.of(box),
                        java.util.List.of(box), candidate.originX(), candidate.originY() + 1, candidate.originZ() - 4);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("could not freeze the poisoned piece", failure);
            }
        }
        @Override public java.util.List<String> validatePrepared(Prepared structure, MacroTerrain terrain) {
            return java.util.List.of();
        }
    };

    private static void assertFrozenBox(GameTestHelper helper, AdventurePlanView.PlannedPiece frozen,
                                        net.minecraft.world.level.levelgen.structure.BoundingBox box, String what) {
        helper.assertTrue(box.minX() == frozen.minX() && box.minY() == frozen.minY()
                        && box.minZ() == frozen.minZ() && box.maxX() == frozen.maxX()
                        && box.maxY() == frozen.maxY() && box.maxZ() == frozen.maxZ(),
                what + " does not occupy the frozen box");
    }

    private static CompoundTag frozenTag(AdventurePlanView.PlannedPiece frozen) {
        try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(frozen.canonicalNbt()))) {
            return NbtIo.read(input);
        } catch (java.io.IOException failure) {
            throw new AssertionError("could not decode frozen NBT for " + frozen.pieceId(), failure);
        }
    }

    private static AdventurePlanView.PlannedPiece frozenPiece(String type, String pieceId) {
        var tag = new CompoundTag();
        tag.putString("id", type);
        tag.putInt("GD", 0);
        try (var bytes = new java.io.ByteArrayOutputStream();
             var output = new java.io.DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
            output.flush();
            return new AdventurePlanView.PlannedPiece(pieceId, 0, 64, 0, 1, 65, 1, bytes.toByteArray());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not build a frozen piece tag for " + type, failure);
        }
    }

    private static ProtoChunk protoChunk(ServerLevel level, RegistryAccess registries, ChunkPos pos) {
        return new ProtoChunk(pos, UpgradeData.EMPTY, level, registries.registryOrThrow(Registries.BIOME), null);
    }

    /** No structure set at all: the vanilla pass assembles nothing, so the injection stands alone. */
    private static HolderLookup<StructureSet> noStructureSets() {
        return new HolderLookup<>() {
            @Override public java.util.stream.Stream<Holder.Reference<StructureSet>> listElements() {
                return java.util.stream.Stream.empty();
            }
            @Override public java.util.stream.Stream<HolderSet.Named<StructureSet>> listTags() {
                return java.util.stream.Stream.empty();
            }
            @Override public java.util.Optional<Holder.Reference<StructureSet>> get(ResourceKey<StructureSet> key) {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<HolderSet.Named<StructureSet>> get(TagKey<StructureSet> key) {
                return java.util.Optional.empty();
            }
        };
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
