package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlannedStructurePlacement;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.worldgen.*;
import io.github.luoyan.adventureworldgen.worldgen.structure.*;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@GameTestHolder("testcompanion_structure")
@PrefixGameTestTemplate(false)
public final class StructureExecutionGameTests {
    private static final List<PlannedStructurePlacement> PLACEMENTS = List.of(
            new PlannedStructurePlacement("template", new ContentId("testcompanion:execution_template"), 15, 15),
            new PlannedStructurePlacement("jigsaw", new ContentId("testcompanion:execution_jigsaw"), -193, 15),
            new PlannedStructurePlacement("java", new ContentId("testcompanion:execution_java"), 15, -193));

    @GameTest(templateNamespace = "testcompanion_structure", template = "empty", timeoutTicks = 6000)
    public static void locateChecksNearestStartsOnlyAndConsumesReturnedReferences(GameTestHelper helper) throws Exception {
        var id = PLACEMENTS.getFirst().structureId();
        // ID order deliberately visits farthest first; the nearest two anchors tie across zero.
        var far = new PlannedStructurePlacement("a_far", id, 769, 15);
        var middle = new PlannedStructurePlacement("b_middle", id, 385, 15);
        var positive = new PlannedStructurePlacement("c_positive", id, 129, 15);
        var negative = new PlannedStructurePlacement("d_negative", id, -129, 15);
        var unrelated = new PlannedStructurePlacement("e_other_type", PLACEMENTS.getLast().structureId(), 1, 1);
        var placements = List.of(far, middle, positive, negative, unrelated);
        var expectedOrder = List.of(positive, negative, middle, far);
        for (boolean coldSkipKnown : List.of(false, true)) {
            var fixture = new Fixture(helper, "locate_" + coldSkipKnown, placements);
            try (var world = fixture.open()) {
                var level = world.level;
                var generator = level.getChunkSource().getGenerator();
                var holder = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolderOrThrow(
                        ResourceKey.create(Registries.STRUCTURE, ResourceLocation.parse(id.value())));
                var targets = HolderSet.direct(holder);
                if (!coldSkipKnown) {
                    // Finite-plan lookup is independent of native random-spread ring radius.
                    for (int radius : List.of(0, 100)) {
                        var found = generator.findNearestMapStructure(level, targets, BlockPos.ZERO, radius, false);
                        helper.assertTrue(found != null && found.getFirst().getX() == positive.anchorX()
                                && found.getFirst().getZ() == positive.anchorZ() && found.getSecond().equals(holder),
                                "ordinary locate changed nearest/tie selection");
                        helper.assertTrue(start(level, positive).getReferences() == 0, "ordinary locate consumed a reference");
                        for (var untouched : List.of(negative, middle, far, unrelated)) assertStartNotLoaded(helper, level, untouched);
                    }
                }
                for (int i = 0; i < expectedOrder.size(); i++) {
                    var expected = expectedOrder.get(i);
                    var found = generator.findNearestMapStructure(level, targets, BlockPos.ZERO, 100, true);
                    helper.assertTrue(found != null && found.getFirst().getX() == expected.anchorX()
                            && found.getFirst().getZ() == expected.anchorZ() && found.getSecond().equals(holder),
                            "skipKnown did not select the nearest unreferenced start: " + expected.instanceId());
                    for (int consumed = 0; consumed <= i; consumed++)
                        helper.assertTrue(start(level, expectedOrder.get(consumed)).getReferences() == 1,
                                "skipKnown consumed a start more than once");
                    for (int untouched = i + 1; untouched < expectedOrder.size(); untouched++)
                        assertStartNotLoaded(helper, level, expectedOrder.get(untouched));
                    assertStartNotLoaded(helper, level, unrelated);
                }
                helper.assertTrue(generator.findNearestMapStructure(level, targets, BlockPos.ZERO, 100, true) == null,
                        "skipKnown returned an exhausted planned start");
                var known = generator.findNearestMapStructure(level, targets, BlockPos.ZERO, 100, false);
                helper.assertTrue(known != null && known.getFirst().getX() == positive.anchorX(),
                        "ordinary locate incorrectly skipped a referenced start");
                for (var p : expectedOrder)
                    helper.assertTrue(start(level, p).getReferences() == 1, "exhausted/ordinary lookup changed references");
                assertStartNotLoaded(helper, level, unrelated);
            }
        }
        helper.succeed();
    }

    private static void assertStartNotLoaded(GameTestHelper helper, ServerLevel level, PlannedStructurePlacement placement) {
        var owner = PlannedStructureBridge.owner(placement);
        var chunk = level.getChunkSource().getChunk(owner.x, owner.z, ChunkStatus.STRUCTURE_STARTS, false);
        helper.assertTrue(chunk == null || !chunk.getPersistedStatus().isOrAfter(ChunkStatus.STRUCTURE_STARTS),
                "locate eagerly loaded an unnecessary start: " + placement.instanceId());
    }

    @GameTest(templateNamespace = "testcompanion_structure", template = "empty", timeoutTicks = 6000)
    public static void realChunksGenerateInEitherOrderAndResumeAfterReload(GameTestHelper helper) throws Exception {
        var first = new Fixture(helper, "forward");
        Map<String, CompoundTag> saved = new TreeMap<>();
        try (var world = first.open()) {
            var p = PLACEMENTS.getFirst();
            ChunkPos startPos = PlannedStructureBridge.owner(p);
            world.level.getChunk(startPos.x, startPos.z, ChunkStatus.FEATURES);
            var start = start(world.level, p);
            saved.put(p.instanceId(), tag(world.level, start));
            helper.assertTrue(world.level.getBlockState(new BlockPos(15, start.getPieces().getFirst().getBoundingBox().minY(), 15)).is(Blocks.GOLD_BLOCK),
                    "first template chunk did not place blocks");
            var far = world.level.getChunk(2, 1, ChunkStatus.STRUCTURE_STARTS);
            helper.assertTrue(!far.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES), "placing first chunk generated far structure blocks eagerly");
            // Save a player edit in the generated piece. Loading this chunk must not replay FEATURES.
            world.level.getChunk(startPos.x, startPos.z, ChunkStatus.FULL);
            world.level.setBlock(new BlockPos(15, start.getPieces().getFirst().getBoundingBox().minY(), 15), Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
        }
        Map<String, String> forward;
        try (var world = first.open()) {
            var start = start(world.level, PLACEMENTS.getFirst());
            helper.assertTrue(saved.get("template").equals(tag(world.level, start)), "native chunk reload changed start/pieces/terrain metadata");
            helper.assertTrue(world.level.getBlockState(new BlockPos(15, start.getPieces().getFirst().getBoundingBox().minY(), 15)).is(Blocks.EMERALD_BLOCK),
                    "loading completed chunk overwrote player edit");
            // Restore the fixture marker so the two traversal snapshots can be compared.
            world.level.setBlock(new BlockPos(15, start.getPieces().getFirst().getBoundingBox().minY(), 15), Blocks.GOLD_BLOCK.defaultBlockState(), 2);
            forward = complete(helper, world.level, false);
        }
        var reverse = new Fixture(helper, "reverse");
        try (var world = reverse.open()) {
            // Reach the far edge before visiting the template's origin: native requirements must establish its start.
            world.level.getChunk(2, 1, ChunkStatus.FEATURES);
            helper.assertTrue(start(world.level, PLACEMENTS.getFirst()).isValid(), "edge-first request did not discover the planned start");
            helper.assertTrue(forward.equals(complete(helper, world.level, true)), "chunk traversal order changed structure blocks or supports");
        }
        helper.succeed();
    }

    private static Map<String, String> complete(GameTestHelper helper, ServerLevel level, boolean reverse) {
        var result = new TreeMap<String, String>();
        var placements = new ArrayList<>(PLACEMENTS);
        if (reverse) Collections.reverse(placements);
        for (var placement : placements) {
            var start = start(level, placement);
            var box = start.getBoundingBox();
            var chunks = new ArrayList<ChunkPos>();
            for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++)
                for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) chunks.add(new ChunkPos(x, z));
            if (reverse) Collections.reverse(chunks);
            for (var pos : chunks) level.getChunk(pos.x, pos.z, ChunkStatus.FEATURES);
            var data = ((ExecutionDataHolder) (Object) start).adventureworldgen$getExecutionData();
            helper.assertTrue(data != null && data.instanceId().equals(placement.instanceId()), "lost instance metadata");
            helper.assertTrue(!data.foundations().isEmpty(), "missing type-specific terrain supports");
            int blocks = 0;
            var content = new StringBuilder();
            for (var piece : start.getPieces()) {
                var b = piece.getBoundingBox();
                for (int x = b.minX(); x <= b.maxX(); x++) for (int z = b.minZ(); z <= b.maxZ(); z++)
                    for (int y = b.minY() - 1; y <= b.maxY(); y++) {
                        var state = level.getChunk(x >> 4, z >> 4, ChunkStatus.FEATURES).getBlockState(new BlockPos(x, y, z));
                        content.append(x).append(',').append(y).append(',').append(z).append('=').append(state).append(';');
                        if (state.is(Blocks.GOLD_BLOCK) || state.is(Blocks.DIAMOND_BLOCK) || state.is(Blocks.EMERALD_BLOCK)) blocks++;
                    }
            }
            helper.assertTrue(blocks >= 160, "structure missing cross-chunk blocks: " + placement.instanceId());
            for (var f : data.foundations()) {
                for (int x = f.minX(); x <= f.maxX(); x++) for (int z = f.minZ(); z <= f.maxZ(); z++)
                    helper.assertTrue(level.getChunk(x >> 4, z >> 4, ChunkStatus.FEATURES)
                            .getBlockState(new BlockPos(x, f.surface() - 1, z)).blocksMotion(), "foundation seam/gap");
            }
            if (placement.instanceId().equals("jigsaw")) helper.assertTrue(start.getPieces().size() >= 2, "jigsaw pool did not assemble child piece");
            if (placement.instanceId().equals("java")) {
                var b = start.getPieces().getFirst().getBoundingBox();
                helper.assertTrue(level.getChunk(b.minX() >> 4, b.minZ() >> 4, ChunkStatus.FEATURES)
                        .getBlockState(new BlockPos(b.minX(), b.minY() + 2, b.minZ())).is(Blocks.EMERALD_BLOCK), "Java afterPlace callback lost");
                helper.assertTrue(b.minX() == PlannedStructureBridge.owner(placement).getMiddleBlockX(), "Java structure was translated from its native origin");
            }
            var holder = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolderOrThrow(
                    ResourceKey.create(Registries.STRUCTURE, ResourceLocation.parse(placement.structureId().value())));
            var located = level.getChunkSource().getGenerator().findNearestMapStructure(level, net.minecraft.core.HolderSet.direct(holder), BlockPos.ZERO, 100, false);
            helper.assertTrue(located != null && located.getFirst().getX() == placement.anchorX()
                    && located.getFirst().getZ() == placement.anchorZ(), "locate ignored planned instance");
            result.put(placement.instanceId(), content.toString());
            // Each native piece codec and our start metadata must also support an immediate NBT round trip.
            var tag = tag(level, start);
            var restored = StructureStart.loadStaticStart(StructurePieceSerializationContext.fromLevel(level), tag, level.getSeed());
            helper.assertTrue(restored != null && tag.equals(tag(level, restored)), "start serialization round trip failed");
        }
        var managedJava = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.parse("testcompanion:execution_java"));
        helper.assertTrue(level.getChunk(4, 4, ChunkStatus.STRUCTURE_STARTS).getStartForStructure(managedJava) == null,
                "managed structure still generated a random native candidate");
        return result;
    }

    @GameTest(templateNamespace = "testcompanion_structure", template = "empty", timeoutTicks = 1200)
    public static void invalidInputsFailBeforeSilentStructureLoss(GameTestHelper helper) throws Exception {
        var registries = helper.getLevel().registryAccess();
        var ids = Set.of(ResourceLocation.parse("testcompanion:execution_template"));
        var catalog = StructureExecutionCatalog.load(ids, registries, helper.getLevel().getServer().getResourceManager(), helper.getLevel().getStructureManager());
        boolean failed = false;
        try { new PlannedStructureBridge(catalog, List.of(PLACEMENTS.getFirst(),
                new PlannedStructurePlacement("duplicate", PLACEMENTS.getFirst().structureId(), 14, 14))); }
        catch (IllegalStateException expected) { failed = expected.getMessage().contains("multiple starts"); }
        helper.assertTrue(failed, "same-ID same-chunk collision was silently overwritten");
        var nativeStructure = registries.registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.parse("minecraft:desert_pyramid"));
        failed = false;
        try { StructureExecutionCatalog.validate(nativeStructure, new TerrainSettings(TerrainSettings.Mode.FLATTEN, 12), helper.getLevel().getStructureManager()); }
        catch (IllegalStateException expected) { failed = true; }
        helper.assertTrue(failed, "unsupported Java footprint was guessed");
        var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, registries);
        var template = (TemplateStructure) registries.registryOrThrow(Registries.STRUCTURE).get(ids.iterator().next());
        var definition = TemplateStructure.CODEC.codec().encodeStart(ops, template).getOrThrow().getAsJsonObject();
        definition.addProperty("template", "testcompanion:missing_execution_template");
        var missing = TemplateStructure.CODEC.codec().parse(ops, definition).getOrThrow();
        failed = false;
        try { missing.validateTemplate(helper.getLevel().getStructureManager()); }
        catch (IllegalStateException expected) { failed = true; }
        helper.assertTrue(failed, "missing template accepted");
        String identity = StructureExecutionIdentity.hash(helper.getLevel().getServer(), catalog, "fixture-plan");
        helper.assertTrue(identity.equals(StructureExecutionIdentity.hash(helper.getLevel().getServer(), catalog, "fixture-plan")),
                "execution fingerprint changed with a warm template cache");
        helper.assertTrue(!identity.equals(StructureExecutionIdentity.hash(helper.getLevel().getServer(), catalog, "changed-plan")),
                "plan identity missing from execution compatibility guard");
        var file = Files.createTempDirectory(helper.getLevel().getServer().getWorldPath(LevelResource.ROOT), "structure-identity-");
        GenerationIdentityFile.verifyOrCreate(file, "first");
        GenerationIdentityFile.verifyOrCreate(file, "first");
        failed = false;
        try { GenerationIdentityFile.verifyOrCreate(file, "changed"); }
        catch (IllegalStateException expected) { failed = true; }
        helper.assertTrue(failed, "changed resources accepted in existing structure world");
        helper.succeed();
    }

    @GameTest(templateNamespace = "testcompanion_structure", template = "empty", timeoutTicks = 1200)
    public static void templateFootprintsMatchNativeStartsAndRotation(GameTestHelper helper) throws Exception {
        var level=helper.getLevel();var registries=level.registryAccess();
        var templates=level.getStructureManager();var generator=level.getChunkSource().getGenerator();
        var original=(TemplateStructure)registries.registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.parse("testcompanion:execution_template"));
        var config=new AdventureWorldConfigParser().parse("""
            {"world":{"radius":512},"spawn":{"biome":"minecraft:plains"},"biomes":{"filler":["minecraft:plains"]},
             "structures":[{"id":"testcompanion:execution_template","adventure_level":1,"count":{"min":1,"max":1},"allowed_biomes":{"id":["minecraft:plains"]}}]}
            """);
        var profile=new io.github.luoyan.adventureworldgen.config.LoadedProfile(new ContentId("testcompanion:template-metadata"),config,
                io.github.luoyan.adventureworldgen.config.CanonicalConfigJson.write(config),"fixture");
        var execution=StructureExecutionCatalog.load(Set.of(ResourceLocation.parse("testcompanion:execution_template")),registries,
                level.getServer().getResourceManager(),templates);
        var resolved=StructureFootprintResources.resolve(profile,registries,templates,execution);
        helper.assertTrue(resolved.structurePlanning().find(new ContentId("testcompanion:execution_template")).orElseThrow().templateFootprint()!=null,
                "startup resource enrichment lost template geometry");
        var adapters=MinecraftAdapters.builtIn();
        helper.assertTrue(!io.github.luoyan.adventureworldgen.runtime.PlanIdentity.hash(17,profile,adapters,io.github.luoyan.adventureworldgen.plan.PlannerProfile.V2)
                .equals(io.github.luoyan.adventureworldgen.runtime.PlanIdentity.hash(17,resolved,adapters,io.github.luoyan.adventureworldgen.plan.PlannerProfile.V2)),
                "resource-derived footprint did not enter the READY identity");
        var ops=net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,registries);
        for(int orientation:List.of(0,1,2,3,-1)) {
            var json=TemplateStructure.CODEC.codec().encodeStart(ops,original).getOrThrow().getAsJsonObject();
            if(orientation<0)json.remove("rotation");else json.addProperty("rotation",net.minecraft.world.level.block.Rotation.values()[orientation].getSerializedName());
            var template=TemplateStructure.CODEC.codec().parse(ops,json).getOrThrow();
            for(var mode:List.of(TerrainSettings.Mode.NONE,TerrainSettings.Mode.FLATTEN)) {
                var settings=new TerrainSettings(mode,8);var predictions=template.planningBounds(templates,settings);
                for(long seed:List.of(0L,17L,91823L))for(int x:List.of(-33,21)) {
                    var anchor=new BlockPos(x,0,-17);var chunk=new ChunkPos(anchor);
                    var context=new Structure.GenerationContext(registries,generator,generator.getBiomeSource(),level.getChunkSource().randomState(),
                            templates,seed,chunk,level,biome->true);
                    int selected=predictions.size()==1?0:io.github.luoyan.adventureworldgen.spatial.TemplateRotation.index(seed,chunk.x,chunk.z);
                    if(predictions.size()==4)helper.assertTrue(selected==net.minecraft.world.level.block.Rotation.getRandom(context.random()).ordinal(),"rotation differs from native first draw");
                    var start=new TemplateStructureExecutor().generate(template,context,anchor);
                    var foundations=mode==TerrainSettings.Mode.FLATTEN?template.terrainSupports(start):List.<Foundation>of();
                    ((ExecutionDataHolder)(Object)start).adventureworldgen$setExecutionData(new StructureExecutionData("test",settings,foundations));
                    var actual=start.getBoundingBox();var expected=predictions.get(selected).exclusion();
                    helper.assertTrue(actual.minX()==expected.minX()+x&&actual.maxX()==expected.maxX()+x
                            &&actual.minZ()==expected.minZ()-17&&actual.maxZ()==expected.maxZ()-17,"predicted template influence differs from native start");
                }
            }
        }
        helper.succeed();
    }

    private static StructureStart start(ServerLevel level, PlannedStructurePlacement p) {
        var owner = PlannedStructureBridge.owner(p);
        var chunk = level.getChunk(owner.x, owner.z, ChunkStatus.STRUCTURE_STARTS);
        var value = chunk.getStartForStructure(level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.parse(p.structureId().value())));
        if (value == null || !value.isValid()) throw new IllegalStateException("missing planned start " + p.instanceId());
        return value;
    }
    private static CompoundTag tag(ServerLevel level, StructureStart start) {
        return start.createTag(StructurePieceSerializationContext.fromLevel(level), start.getChunkPos());
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final Path directory;
        final ResourceLocation profile;
        final GeneratedAdventurePlan plan;
        final StructureExecutionCatalog catalog;
        Fixture(GameTestHelper helper, String name) throws Exception {
            this(helper, name, PLACEMENTS);
        }
        Fixture(GameTestHelper helper, String name, List<PlannedStructurePlacement> placements) throws Exception {
            this.helper = helper;
            directory = Files.createTempDirectory(helper.getLevel().getServer().getWorldPath(LevelResource.ROOT), "structure-" + name + "-");
            profile = ResourceLocation.fromNamespaceAndPath("testcompanion_structure", name + "_" + directory.getFileName());
            var config = new AdventureWorldConfigParser().parse("""
                    {"world":{"radius":1024},"spawn":{"biome":"testcompanion:structure_fixture"},
                     "biomes":{"filler":["testcompanion:structure_fixture"]}}
                    """);
            long seed = helper.getLevel().getSeed();
            plan = new GeneratedAdventurePlan(seed, config, new Coastline(List.of(new Vec2(-1024,-1024), new Vec2(1024,-1024),
                    new Vec2(1024,1024), new Vec2(-1024,1024))), new RiverNetwork(List.of(), List.of(), "structure-test"),
                    64, 128, 256, "structure-test", null, List.of(), placements, null, null);
            RuntimePlanRegistry.start(new ContentId(profile.toString()), () -> plan).join();
            var server = helper.getLevel().getServer();
            catalog = StructureExecutionCatalog.load(placements.stream().map(p -> ResourceLocation.parse(p.structureId().value()))
                    .collect(java.util.stream.Collectors.toSet()), server.registryAccess(), server.getResourceManager(), server.getStructureManager());
        }
        OpenWorld open() throws Exception {
            var server = helper.getLevel().getServer();
            var registries = server.registryAccess();
            var generator = new AdventureChunkGenerator(profile, registries.lookupOrThrow(Registries.BIOME),
                    registries.lookupOrThrow(Registries.NOISE_SETTINGS), registries.lookupOrThrow(Registries.NOISE));
            generator.prepareStructureExecution(catalog, plan);
            var access = LevelStorageSource.createDefault(directory).createAccess("chunks");
            var stem = new LevelStem(registries.registryOrThrow(Registries.DIMENSION_TYPE).getHolderOrThrow(BuiltinDimensionTypes.OVERWORLD), generator);
            var level = new ServerLevel(server, Util.backgroundExecutor(), access,
                    new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData()),
                    ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("testcompanion_structure:fixture")), stem,
                    LoggerChunkProgressListener.createCompleted(), false, plan.seed(), List.of(), false, null);
            return new OpenWorld(level, access);
        }
    }
    private record OpenWorld(ServerLevel level, LevelStorageSource.LevelStorageAccess access) implements AutoCloseable {
        @Override public void close() throws Exception {
            try { level.save(null, true, false); level.close(); } finally { access.close(); }
        }
    }
}
