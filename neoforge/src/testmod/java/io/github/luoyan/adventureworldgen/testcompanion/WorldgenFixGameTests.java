package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.runtime.RuntimePlanRegistry;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.worldgen.AdventureChunkGenerator;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.neoforged.neoforge.gametest.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;

@GameTestHolder("testcompanion_performance")
@PrefixGameTestTemplate(false)
public final class WorldgenFixGameTests {
    private static AdventureChunkGenerator generator(GameTestHelper helper, String name) {
        var config = new AdventureWorldConfigParser().parse("""
                {"world":{"radius":512},"spawn":{"biome":"minecraft:plains"},
                 "biomes":{"filler":["minecraft:plains"]}}
                """);
        var plan = new GeneratedAdventurePlan(7331, config,
                new Coastline(List.of(new Vec2(-512,-512), new Vec2(512,-512),
                        new Vec2(512,512), new Vec2(-512,512))),
                new RiverNetwork(List.of(), List.of(), "test"), 64, 128, 256, "test", null);
        var id = ResourceLocation.fromNamespaceAndPath("testcompanion_performance", name);
        RuntimePlanRegistry.start(new ContentId(id.toString()), () -> plan).join();
        var registries = helper.getLevel().registryAccess();
        return new AdventureChunkGenerator(id, registries.lookupOrThrow(Registries.BIOME),
                registries.lookupOrThrow(Registries.NOISE_SETTINGS), registries.lookupOrThrow(Registries.NOISE));
    }

    @GameTest(templateNamespace="testcompanion_performance", template="empty", timeoutTicks=1200)
    public static void villageFoundationsCrossChunkEdges(GameTestHelper helper) {
        var level = helper.getLevel();
        var generator = generator(helper, "foundations");
        var random = level.getChunkSource().randomState();
        var registries = level.registryAccess();
        var village = registries.registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.parse("minecraft:village_plains"));
        var start = village.generate(registries, generator, generator.getBiomeSource(), random,
                level.getStructureManager(), 7331, new ChunkPos(0,0), 0, level, biome -> true);
        helper.assertTrue(start.isValid(), "vanilla village did not assemble");
        var house = start.getPieces().stream().filter(p -> p instanceof PoolElementStructurePiece pool
                && pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID
                && p.getBoundingBox().getXSpan() >= 5 && p.getBoundingBox().getZSpan() >= 5)
                .map(p -> (PoolElementStructurePiece)p).findFirst().orElseThrow();
        var box = house.getBoundingBox();
        house.move(14-box.minX(), 0, 14-box.minZ());
        int original = generator.getBaseHeight(16,16,Heightmap.Types.OCEAN_FLOOR_WG,level,random);
        house.move(0, original+16-box.minY()-house.getGroundLevelDelta(), 0);
        int floor = box.minY()+house.getGroundLevelDelta();
        var isolated = new StructureStart(village, new ChunkPos(0,0), 0, new PiecesContainer(List.of(house)));
        var structures = new StructureManager(level, new WorldOptions(7331,false,false), null) {
            @Override public List<StructureStart> startsForStructure(ChunkPos pos,
                    java.util.function.Predicate<Structure> predicate) {
                return predicate.test(village) ? List.of(isolated) : List.of();
            }
        };
        int columns=0, chunks=0;
        for(int cx=Math.floorDiv(box.minX(),16);cx<=Math.floorDiv(box.maxX(),16);cx++)
            for(int cz=Math.floorDiv(box.minZ(),16);cz<=Math.floorDiv(box.maxZ(),16);cz++) {
                var chunk = new ProtoChunk(new ChunkPos(cx,cz),UpgradeData.EMPTY,level,
                        registries.registryOrThrow(Registries.BIOME),null);
                generator.createBiomes(random,Blender.empty(),structures,chunk).join();
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                var filled=generator.fillFromNoise(Blender.empty(),random,structures,chunk);
                level.getServer().managedBlock(filled::isDone);filled.join();
                generator.buildPlannedSurface(registries,chunk);chunks++;
                for(int x=Math.max(box.minX(),cx*16);x<=Math.min(box.maxX(),cx*16+15);x++)
                    for(int z=Math.max(box.minZ(),cz*16);z<=Math.min(box.maxZ(),cz*16+15);z++) {
                        helper.assertTrue(chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG,x&15,z&15)==floor-1,
                                "foundation height differs from jigsaw ground datum");
                        int base=generator.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,random);
                        for(int y=Math.min(base,floor)-1;y<floor;y++)helper.assertTrue(
                                chunk.getBlockState(new BlockPos(x,y,z)).blocksMotion(),"floating foundation gap");
                        columns++;
                    }
            }
        helper.assertTrue(chunks>=4 && columns>=25,"fixture did not cover chunk seams");
        helper.succeed();
    }

    @GameTest(templateNamespace="testcompanion_performance", template="empty", timeoutTicks=1200)
    public static void snowSpringsAreFilteredBeforeFluidTicks(GameTestHelper helper) {
        var generator=generator(helper,"springs");
        var blocks=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var pos=new BlockPos(0,160,0);
        var snow=helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME)
                .getHolderOrThrow(net.minecraft.world.level.biome.Biomes.FROZEN_PEAKS);
        int[] roof={160},ticks={0};
        WorldGenLevel world=(WorldGenLevel)java.lang.reflect.Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class},(proxy,method,args)->switch(method.getName()) {
                    case "getBiome" -> snow;
                    case "getBlockState" -> blocks.getOrDefault(args[0],Blocks.AIR.defaultBlockState());
                    case "isEmptyBlock" -> !blocks.containsKey(args[0]) || blocks.get(args[0]).isAir();
                    case "getHeight" -> roof[0];
                    case "setBlock" -> {blocks.put(((BlockPos)args[0]).immutable(),
                            (net.minecraft.world.level.block.state.BlockState)args[1]);yield true;}
                    case "scheduleTick" -> {ticks[0]++;yield null;}
                    default -> throw new UnsupportedOperationException(method.toString());
                });
        var spring=new SpringConfiguration(net.minecraft.world.level.material.Fluids.WATER.defaultFluidState(),
                true,4,1,HolderSet.direct(Blocks.STONE.builtInRegistryHolder()));
        for(var offset:List.of(pos,pos.above(),pos.below(),pos.north(),pos.south(),pos.west()))
            blocks.put(offset,Blocks.STONE.defaultBlockState());
        var context=new FeaturePlaceContext<>(Optional.empty(),world,generator,
                net.minecraft.util.RandomSource.create(1),pos,spring);
        helper.assertTrue(!Feature.SPRING.place(context),"exposed cold spring was placed");
        helper.assertTrue(ticks[0]==0 && blocks.get(pos).is(Blocks.STONE),"water tick escaped filter");
        roof[0]=200;
        helper.assertTrue(Feature.SPRING.place(context),"underground spring was suppressed");
        helper.assertTrue(ticks[0]==1 && blocks.get(pos).is(Blocks.WATER),"underground water missing");
        blocks.put(pos,Blocks.STONE.defaultBlockState());roof[0]=160;
        var vanilla=new FeaturePlaceContext<>(Optional.empty(),world,helper.getLevel().getChunkSource().getGenerator(),
                net.minecraft.util.RandomSource.create(1),pos,spring);
        helper.assertTrue(Feature.SPRING.place(vanilla),"filter affected another chunk generator");
        helper.succeed();
    }
}
