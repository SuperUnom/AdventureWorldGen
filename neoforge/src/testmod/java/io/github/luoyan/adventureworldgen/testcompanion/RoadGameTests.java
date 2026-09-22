package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.persistence.PlanV2Codec;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.runtime.*;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.worldgen.*;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.*;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.*;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

@GameTestHolder("testcompanion_roads")
@PrefixGameTestTemplate(false)
public final class RoadGameTests {
    @GameTest(templateNamespace="testcompanion_roads",template="empty",timeoutTicks=1800)
    public static void frozenRoadsRespectChunkOrderAndPlayerEdits(GameTestHelper helper) throws Exception {
        var first=new Fixture(helper,"forward");var reverse=new Fixture(helper,"reverse");
        helper.assertTrue(!first.plan.roads().routes().isEmpty(),"road planner emitted no roads");
        Map<String,String> expected;
        BlockPos edit;
        try(var world=first.open()) {
            expected=complete(helper,world.level,first.plan,false);
            var column=first.plan.roads().columns().get(first.plan.roads().columns().size()/2);
            edit=new BlockPos(column.x(),column.deckY(),column.z());
            world.level.getChunk(edit.getX()>>4,edit.getZ()>>4,ChunkStatus.FULL);
            world.level.setBlock(edit,Blocks.DIAMOND_BLOCK.defaultBlockState(),2);
        }
        try(var world=first.open()) {
            world.level.getChunk(edit.getX()>>4,edit.getZ()>>4,ChunkStatus.FULL);
            helper.assertTrue(world.level.getBlockState(edit).is(Blocks.DIAMOND_BLOCK),"loading repaved a player edit");
        }
        try(var world=reverse.open()) {
            helper.assertTrue(expected.equals(complete(helper,world.level,reverse.plan,true)),"chunk order changed frozen road blocks");
        }
        helper.succeed();
    }
    @GameTest(templateNamespace="testcompanion_roads",template="empty",timeoutTicks=1800)
    public static void neighbouringDecorationCannotOverwriteRoadOrHeadroom(GameTestHelper helper) throws Exception {
        var fixture=new Fixture(helper,"decoration");
        try(var world=fixture.open()) {
            complete(helper,world.level,fixture.plan,false);
            var column=fixture.plan.roads().columns().stream().filter(c->Math.floorMod(c.x(),16)==15).findFirst().orElseThrow();
            var pos=new BlockPos(column.x(),column.deckY(),column.z());
            // The guarded writes return before consulting the region cache. Put the centre in the
            // neighbouring chunk to exercise target-coordinate protection, not an origin-only test.
            var center=world.level.getChunk((column.x()>>4)+1,column.z()>>4,ChunkStatus.FEATURES);
            var region=new WorldGenRegion(world.level,StaticCache2D.create(center.getPos().x,center.getPos().z,0,(x,z)->(GenerationChunkHolder)null),
                    ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES),center);
            helper.assertTrue(!region.setBlock(pos,Blocks.OAK_LOG.defaultBlockState(),2),"feature overwrote deck");
            helper.assertTrue(!region.setBlock(pos.above(),Blocks.OAK_LEAVES.defaultBlockState(),2),"neighbour feature blocked headroom");
            helper.assertTrue(!region.setBlock(pos.below(),Blocks.AIR.defaultBlockState(),2),"feature removed road support");
        }
        helper.succeed();
    }
    @GameTest(templateNamespace="testcompanion_roads",template="empty",timeoutTicks=1800)
    public static void shortBridgePreservesWaterAndItsApproaches(GameTestHelper helper) throws Exception {
        var fixture=new Fixture(helper,"bridge",true);
        helper.assertTrue(fixture.plan.roads().columns().stream().anyMatch(RoadPlan.Column::bridge),"fixture did not build a bridge");
        try(var world=fixture.open()) {
            complete(helper,world.level,fixture.plan,true);
            for(var c:fixture.plan.roads().columns())if(c.bridge()) {
                var chunk=world.level.getChunk(c.x()>>4,c.z()>>4,ChunkStatus.FEATURES);
                int water=(int)Math.floor(fixture.plan.terrainAt(c.x()+.5,c.z()+.5).waterSurface())-1;
                helper.assertTrue(!chunk.getBlockState(new BlockPos(c.x(),water,c.z())).getFluidState().isEmpty(),"bridge filled or drained its river");
                helper.assertTrue(chunk.getBlockState(new BlockPos(c.x(),c.bottomY(),c.z())).is(Blocks.OAK_PLANKS),"bridge beam missing");
            }
        }
        helper.succeed();
    }
    @GameTest(templateNamespace="testcompanion_roads",template="empty",timeoutTicks=1800)
    public static void layeredBoardwalksPreserveAirAndChunkOrder(GameTestHelper helper) throws Exception {
        var forward=new Fixture(helper,"layered_forward",false,true);
        var reverse=new Fixture(helper,"layered_reverse",false,true);
        Map<String,String> expected;
        try(var world=forward.open()) {
            expected=complete(helper,world.level,forward.plan,false);
            helper.assertTrue(forward.plan.roadsAt(15,0).size()==2,"fixture lost its stacked decks");
            helper.assertTrue(world.level.getBlockState(new BlockPos(15,208,0)).isAir(),"boardwalk filled its lower natural space");
            helper.assertTrue(world.level.getBlockState(new BlockPos(13,214,0)).is(Blocks.OAK_LOG),"cross-chunk support missing");
        }
        try(var world=reverse.open()) {
            helper.assertTrue(expected.equals(complete(helper,world.level,reverse.plan,true)),"layered deck depends on chunk order");
            helper.assertTrue(world.level.getBlockState(new BlockPos(13,214,0)).is(Blocks.OAK_LOG),"reverse support missing");
        }
        RuntimePlanRegistry.release(new ContentId(forward.profile.toString()),forward.plan);
        RuntimePlanRegistry.release(new ContentId(reverse.profile.toString()),reverse.plan);
        helper.succeed();
    }
    private static RoadPlan layeredRoad() {
        var path=new ArrayList<int[]>();
        for(int x=15;x<=31;x++)path.add(new int[]{x,0});
        for(int z=1;z<=16;z++)path.add(new int[]{31,z});
        for(int x=30;x>=15;x--)path.add(new int[]{x,16});
        for(int z=15;z>=0;z--)path.add(new int[]{15,z});
        var columns=new ArrayList<RoadPlan.Column>();var geometry=new ArrayList<RoadPlan.Point3>();
        for(int i=0;i<path.size();i++) {
            int x=path.get(i)[0],z=path.get(i)[1],y=200+Math.min(i,16);
            columns.add(new RoadPlan.Column(x,z,y,y-1,y+3,true,false,RoadPlan.Kind.BOARDWALK,i>0&&i<=16?0:-1));
            geometry.add(new RoadPlan.Point3(x+.5,y,z+.5));
        }
        columns.sort(RoadPlan.COLUMN_ORDER);
        return new RoadPlan(List.of(new RoadPlan.Node("spawn",15,0,true,200,RoadPlan.NodeKind.DESTINATION),
                new RoadPlan.Node("upper",15,0,true,216,RoadPlan.NodeKind.DESTINATION)),
                List.of(new RoadPlan.Route("loop","spawn","upper",geometry.stream().map(v->new Vec2(v.x(),v.z())).toList(),64,geometry,RoadPlan.Kind.BOARDWALK)),
                columns,List.of(),List.of(),0,List.of(new RoadPlan.Support(13,214,0,15,214,0,RoadPlan.SupportKind.BEAM)));
    }
    private static Map<String,String> complete(GameTestHelper helper,ServerLevel level,GeneratedAdventurePlan plan,boolean reverse) {
        var chunks=plan.roads().columns().stream().map(c->new net.minecraft.world.level.ChunkPos(c.x()>>4,c.z()>>4)).distinct()
                .sorted(Comparator.comparingInt((net.minecraft.world.level.ChunkPos p)->p.x).thenComparingInt(p->p.z)).toList();
        var order=new ArrayList<>(chunks);if(reverse)Collections.reverse(order);
        for(var pos:order)level.getChunk(pos.x,pos.z,ChunkStatus.FEATURES);
        var result=new TreeMap<String,String>();var generator=level.getChunkSource().getGenerator();
        for(var c:plan.roads().columns()) {
            var chunk=level.getChunk(c.x()>>4,c.z()>>4,ChunkStatus.FEATURES);var pos=new BlockPos(c.x(),c.deckY(),c.z());
            helper.assertTrue(chunk.getBlockState(pos).blocksMotion(),"missing road deck at "+pos);
            helper.assertTrue(generator.getBaseHeight(c.x(),c.z(),Heightmap.Types.WORLD_SURFACE_WG,level,level.getChunkSource().randomState())==plan.roadAt(c.x(),c.z()).deckY()+1,"road base height mismatch");
            helper.assertTrue(generator.getBaseColumn(c.x(),c.z(),level,level.getChunkSource().randomState()).getBlock(c.deckY()).equals(chunk.getBlockState(pos)),"road base column mismatch");
            for(int y=c.deckY()+1;y<=c.deckY()+3;y++)helper.assertTrue(chunk.getBlockState(new BlockPos(c.x(),y,c.z())).isAir(),"road headroom obstructed");
            result.put(c.x()+","+c.deckY()+","+c.z(),chunk.getBlockState(pos).toString());
        }
        return result;
    }
    private static final class Fixture {
        final GameTestHelper helper;final Path directory;final ResourceLocation profile;final GeneratedAdventurePlan plan;
        Fixture(GameTestHelper helper,String name) throws Exception {this(helper,name,false);}
        Fixture(GameTestHelper helper,String name,boolean river) throws Exception {this(helper,name,river,false);}
        Fixture(GameTestHelper helper,String name,boolean river,boolean layered) throws Exception {
            this.helper=helper;directory=Files.createTempDirectory(helper.getLevel().getServer().getWorldPath(LevelResource.ROOT),"roads-"+name+"-");
            profile=ResourceLocation.fromNamespaceAndPath("testcompanion_roads",name+"_"+directory.getFileName());
            var templates=new StringJoiner(",");
            for(var t:TerrainTemplate.values())templates.add("\""+t.id()+"\":{\"weight\":"+(t.id().equals("steppe")?1:0)+",\"vertical_amplitude\":4,\"detail_strength\":0.1}");
            var config=new AdventureWorldConfigParser().parse("""
                {"world":{"radius":512,"terrain":{"composite":false,"mountain_ranges":false,"templates":{%s}}},
                 "spawn":{"biome":"minecraft:forest"},"biomes":{"filler":["minecraft:forest"],
                  "required":[{"id":"minecraft:forest","adventure_level":1,"road":{"enabled":true,"required":true}}]},
                 "roads":{"enabled":true}}
                """.formatted(templates));
            var channels=river?List.of(new RiverNetwork.Channel("road-test-river",0,null,
                    List.of(new Vec2(48,-256),new Vec2(48,256)),List.of(0.,512.),List.of(64.,64.),
                    new io.github.luoyan.adventureworldgen.hydrology.HydrologyProfile.RiverShape(4,1,2,8,12,.5),null)):List.<RiverNetwork.Channel>of();
            var original=new GeneratedAdventurePlan(42,config,new Coastline(List.of(new Vec2(-512,-512),new Vec2(512,-512),new Vec2(512,512),new Vec2(-512,512))),
                    new RiverNetwork(channels,List.of(),PlannerProfile.V2.hydrologyVersion()),64,32,64,PlanVersions.TERRAIN,null,
                    List.of(new PlannedBiomePatch("forest",new ContentId("minecraft:forest"),1,110,-2,118,6)),List.of(),
                    PlanDiagnostics.basic(4,channels.size(),channels.size()*2L,PlanVersions.TERRAIN),null);
            var codec=new PlanV2Codec();var id=new ContentId(profile.toString());
            var snapshot=original.snapshot();
            if(layered)snapshot=new io.github.luoyan.adventureworldgen.persistence.PlanSnapshot(snapshot.seed(),snapshot.diagnostics(),snapshot.spawn(),
                    snapshot.coastline(),snapshot.riverNetwork(),snapshot.seaSurface(),snapshot.landBand(),snapshot.seaBand(),snapshot.terrainVersion(),
                    snapshot.recipeSettings(),snapshot.recipeRegions(),snapshot.biomePatches(),snapshot.structures(),snapshot.erosion(),snapshot.capacities(),snapshot.biomeLayout(),layeredRoad());
            plan=GeneratedAdventurePlan.restore(config,codec.decode(codec.encode(id,"roads-fixture",snapshot),id,"roads-fixture"));
            RuntimePlanRegistry.start(id,()->plan).join();
        }
        OpenWorld open() throws Exception { return openWorld(helper,directory,profile,plan); }
    }
    static void assertPlannedVillageStarts(GameTestHelper helper,GeneratedAdventurePlan plan) throws Exception {
        var directory=Files.createTempDirectory(helper.getLevel().getServer().getWorldPath(LevelResource.ROOT),"village-roads-");
        var profile=ResourceLocation.fromNamespaceAndPath("testcompanion_roads",directory.getFileName().toString());
        RuntimePlanRegistry.start(new ContentId(profile.toString()),()->plan).join();
        try(var world=openWorld(helper,directory,profile,plan)) {
            // GameTestServer disables structure generation in its WorldOptions. Exercise the
            // production start entry with an enabled manager and real registries/chunks instead.
            var manager=new net.minecraft.world.level.StructureManager(world.level,
                    new net.minecraft.world.level.levelgen.WorldOptions(plan.seed(),true,false),null);
            var random=net.minecraft.world.level.levelgen.RandomState.create(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.dummy(),
                    world.level.registryAccess().lookupOrThrow(Registries.NOISE),plan.seed());
            var structureState=world.level.getChunkSource().getGenerator().createState(
                    world.level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET),random,plan.seed());
            for(var placement:plan.structures()) {
                var owner=PlannedStructureBridge.owner(placement);
                var chunk=world.level.getChunk(owner.x,owner.z,ChunkStatus.STRUCTURE_STARTS);
                world.level.getChunkSource().getGenerator().createStructures(world.level.registryAccess(),
                        structureState,manager,chunk,world.level.getServer().getStructureManager());
                var structure=world.level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.parse(placement.structureId().value()));
                var start=chunk.getStartForStructure(structure);
                helper.assertTrue(start!=null&&start.isValid(),"village failed native start generation: "+placement.instanceId()+", status="+chunk.getPersistedStatus()+", starts="+chunk.getAllStarts());
            }
        }
    }
    private static OpenWorld openWorld(GameTestHelper helper,Path directory,ResourceLocation profile,GeneratedAdventurePlan plan) throws Exception {
            var server=helper.getLevel().getServer();var registries=server.registryAccess();
            var generator=new AdventureChunkGenerator(profile,registries.lookupOrThrow(Registries.BIOME),registries.lookupOrThrow(Registries.NOISE_SETTINGS),registries.lookupOrThrow(Registries.NOISE));
            generator.prepareStructureExecution(StructureExecutionCatalog.load(plan.structures().stream().map(p->ResourceLocation.parse(p.structureId().value())).collect(java.util.stream.Collectors.toSet()),registries,server.getResourceManager(),server.getStructureManager()),plan);
            var access=LevelStorageSource.createDefault(directory).createAccess("chunks");
            var stem=new LevelStem(registries.registryOrThrow(Registries.DIMENSION_TYPE).getHolderOrThrow(BuiltinDimensionTypes.OVERWORLD),generator);
            var level=new ServerLevel(server,Util.backgroundExecutor(),access,new DerivedLevelData(server.getWorldData(),server.getWorldData().overworldData()),
                    ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse("testcompanion_roads:fixture")),stem,LoggerChunkProgressListener.createCompleted(),false,plan.seed(),List.of(),false,null);
            return new OpenWorld(level,access);
    }
    private record OpenWorld(ServerLevel level,LevelStorageSource.LevelStorageAccess access) implements AutoCloseable {
        public void close() throws Exception {
            try {
                // Forest features can queue POI updates on the shared server executor. Drain them
                // while this fixture's POI storage is still open, before closing its private world.
                var drained=new java.util.concurrent.atomic.AtomicBoolean();
                level.getServer().tell(new net.minecraft.server.TickTask(level.getServer().getTickCount(),()->drained.set(true)));
                level.getServer().managedBlock(drained::get);
                level.save(null,true,false);level.close();
            } finally {access.close();}
        }
    }
}
