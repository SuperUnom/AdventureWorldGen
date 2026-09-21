package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.runtime.*;
import io.github.luoyan.adventureworldgen.worldgen.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Path;
import java.util.*;

@GameTestHolder("testcompanion_planning")
@PrefixGameTestTemplate(false)
public final class RoadRegressionGameTests {
    @GameTest(templateNamespace="testcompanion_planning", template="empty", timeoutTicks=4800)
    public static void reportedRoadBudgetSeedPlansAndReplays(GameTestHelper helper) throws Exception {
        verify(helper,-4587828687004730609L);
    }
    @GameTest(templateNamespace="testcompanion_planning", template="empty", timeoutTicks=4800)
    public static void reportedTaigaDisconnectSeedPlansAndReplays(GameTestHelper helper) throws Exception {
        verify(helper,-1387261685787353557L);
    }
    @GameTest(templateNamespace="testcompanion_planning", template="empty", timeoutTicks=4800)
    public static void reportedVillageDistanceSeedUsesInstanceBounds(GameTestHelper helper) throws Exception {
        verify(helper,1272697740153084795L);
    }
    @GameTest(templateNamespace="testcompanion_planning", template="empty", timeoutTicks=4800)
    public static void reportedCrossingAndSpawnBumpSeedPlansAndReplays(GameTestHelper helper) throws Exception {
        verify(helper,1619297343839528281L);
    }
    private record Instance(PlannedStructurePlacement placement,StructurePlanningInfo info) {}
    private static void verify(GameTestHelper helper,long seed) throws Exception {
        var level=helper.getLevel();var server=level.getServer();var registries=level.registryAccess();
        AdventureWorldConfig config;
        try(var reader=java.nio.file.Files.newBufferedReader(Path.of("../src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json"))) {
            config=new AdventureWorldConfigParser().parse(reader);
        }
        var id=ResourceLocation.fromNamespaceAndPath("testcompanion","road_regression_"+Long.toUnsignedString(seed));
        var loaded=new LoadedProfile(new ContentId(id.toString()),config,CanonicalConfigJson.write(config),"regression",
                StructureRoadInformation.load(server.getResourceManager(),config));
        var managed=config.structures().stream().map(s->ResourceLocation.parse(s.id().value())).collect(java.util.stream.Collectors.toSet());
        var execution=StructureExecutionCatalog.load(managed,registries,server.getResourceManager(),server.getStructureManager());
        loaded=StructureFootprintResources.resolve(loaded,registries,server.getStructureManager(),execution);
        var identity=StructureExecutionIdentity.hash(server,execution,PlanIdentity.hash(seed,loaded,MinecraftAdapters.builtIn(),PlannerProfile.V2));
        var preparation=new StructureInstancePreparation(registries,server.getStructureManager(),execution,config,id);
        var directory=Path.of("road-regression-"+seed);
        var plan=RuntimePlanner.plan(seed,loaded,directory,MinecraftAdapters.builtIn(),identity,(view,catalog)-> {
            var result=preparation.prepare(view,catalog);
            try {
                java.nio.file.Files.createDirectories(directory);
                var codec=new io.github.luoyan.adventureworldgen.persistence.PlanV2Codec();
                java.nio.file.Files.write(directory.resolve("natural.json"),codec.encode(new ContentId(id.toString()),"fixture",view.snapshot()));
                var gson=new com.google.gson.Gson();
                java.nio.file.Files.writeString(directory.resolve("instances.json"),gson.toJson(result.placements().stream()
                        .map(p->new Instance(p,result.catalog().find(p).orElseThrow())).toList()));
            } catch(java.io.IOException failure) {throw new java.io.UncheckedIOException(failure);}
            return result;
        });
        var spawn=plan.spawnPosition();
        int sx=(int)Math.floor(spawn.x()),sz=(int)Math.floor(spawn.z());
        helper.assertTrue(plan.roadAt(sx,sz).deckY()==(int)Math.floor(plan.terrainAt(sx+.5,sz+.5).groundSurface())-1,
                "spawn safety air clearance raised the frozen road");
        RuntimePlanRegistry.start(new ContentId(id.toString()),()->plan).join();
        try {
        var generator=new AdventureChunkGenerator(id,registries.lookupOrThrow(Registries.BIOME),
                registries.lookupOrThrow(Registries.NOISE_SETTINGS),registries.lookupOrThrow(Registries.NOISE));
        generator.prepareStructureExecution(execution,plan);
        var random=net.minecraft.world.level.levelgen.RandomState.create(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.dummy(),registries.lookupOrThrow(Registries.NOISE),seed);
        var state=generator.createState(registries.lookupOrThrow(Registries.STRUCTURE_SET),random,seed);
        var manager=new StructureManager(level,new WorldOptions(seed,true,false),null);
        var before=new TreeMap<String,net.minecraft.nbt.CompoundTag>();
        var order=new ArrayList<>(plan.plannedStructures()); Collections.reverse(order);
        for(var placement:order) {
            var chunk=new ProtoChunk(PlannedStructureBridge.owner(placement),UpgradeData.EMPTY,level,registries.registryOrThrow(Registries.BIOME),null);
            generator.createStructures(registries,state,manager,chunk,server.getStructureManager());
            Structure structure=registries.registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.parse(placement.structureId().value()));
            var start=chunk.getStartForStructure(structure);
            helper.assertTrue(start!=null&&start.isValid(),"native start missing for "+placement);
            var box=start.getBoundingBox();
            var reservation=plan.roads().reservations().stream().filter(r->r.instanceId().equals(placement.instanceId())).findFirst().orElseThrow();
            helper.assertTrue(reservation.bounds().equals(new BoundsXZ(box.minX(),box.minZ(),box.maxX(),box.maxZ())),"road bounds differ from real start");
            var node=plan.roads().nodes().stream().filter(n->n.id().equals("structure/"+placement.instanceId())).findFirst().orElseThrow();
            double dx=Math.max(Math.max(box.minX()-node.x(),node.x()-box.maxX()),0);
            double dz=Math.max(Math.max(box.minZ()-node.z(),node.z()-box.maxZ()),0);
            helper.assertTrue(Math.hypot(dx,dz)<=12,"road endpoint too far from actual instance");
            double pieceDistance=start.getPieces().stream().map(p->p.getBoundingBox()).mapToDouble(b->Math.hypot(
                    Math.max(Math.max(b.minX()-node.x(),node.x()-b.maxX()),0),
                    Math.max(Math.max(b.minZ()-node.z(),node.z()-b.maxZ()),0))).min().orElseThrow();
            helper.assertTrue(pieceDistance<=24,"endpoint faces an empty part of the structure envelope: "+pieceDistance);
            before.put(placement.instanceId(),start.createTag(net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext.fromLevel(level),chunk.getPos()));
        }
        var restored=RuntimePlanner.plan(seed,loaded,directory,MinecraftAdapters.builtIn(),identity,(v,c)->{throw new AssertionError("READY reran preparation");});
        helper.assertTrue(plan.roads().equals(restored.roads()),"READY changed roads");
        helper.assertTrue(plan.plannedStructures().equals(restored.plannedStructures()),"READY changed validated anchors");
        var natural=AdventureChunkGenerator.naturalQueries(registries,id,restored);
        for(var placement:restored.plannedStructures()) {
            var structure=registries.registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.parse(placement.structureId().value()));
            var context=new Structure.GenerationContext(registries,natural,natural.getBiomeSource(),random,
                    server.getStructureManager(),seed,PlannedStructureBridge.owner(placement),level,structure.biomes()::contains);
            var start=PlannedStructureBridge.generateStart(structure,context,placement,execution.terrain(ResourceLocation.parse(placement.structureId().value())));
            helper.assertTrue(start.isValid(),"restored start invalid");
            helper.assertTrue(before.get(placement.instanceId()).equals(start.createTag(net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext.fromLevel(level),PlannedStructureBridge.owner(placement))),"replay changed native pieces");
        }
        helper.succeed();
        } finally {
            // Each seed uses an isolated profile. Keep its large terrain snapshot alive only
            // while native replay needs it, rather than retaining every test world in the JVM.
            RuntimePlanRegistry.release(new ContentId(id.toString()),plan);
        }
    }
}
