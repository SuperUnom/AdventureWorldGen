package io.github.luoyan.adventureworldgen.testcompanion;

import com.mojang.serialization.MapCodec;
import io.github.luoyan.adventureworldgen.worldgen.structure.Foundation;
import io.github.luoyan.adventureworldgen.worldgen.structure.TerrainSupportProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.List;
import java.util.Optional;

/** Registered Java fixture has its own piece serializer and afterPlace hook. No production ID special cases. */
public final class StructureFixture extends Structure implements TerrainSupportProvider {
    public static final MapCodec<StructureFixture> CODEC = simpleCodec(StructureFixture::new);
    private static final DeferredRegister<StructureType<?>> TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, TestCompanion.ID);
    private static final DeferredRegister<StructurePieceType> PIECES = DeferredRegister.create(Registries.STRUCTURE_PIECE, TestCompanion.ID);
    private static final DeferredHolder<StructureType<?>, StructureType<StructureFixture>> TYPE = TYPES.register("java_fixture", () -> () -> CODEC);
    private static final DeferredHolder<StructurePieceType, StructurePieceType> PIECE = PIECES.register("java_fixture", () -> Piece::new);
    public static void register(IEventBus bus) { TYPES.register(bus); PIECES.register(bus); }
    public StructureFixture(StructureSettings settings) { super(settings); }
    @Override public StructureType<?> type() { return TYPE.get(); }
    @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext c) {
        int x = c.chunkPos().getMiddleBlockX(), z = c.chunkPos().getMiddleBlockZ();
        int y = c.chunkGenerator().getFirstFreeHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, c.heightAccessor(), c.randomState()) + 12;
        var pos = new BlockPos(x, y, z);
        return Optional.of(new GenerationStub(pos, builder -> builder.addPiece(new Piece(new BoundingBox(x, y, z, x + 31, y + 2, z + 4)))));
    }
    @Override public List<Foundation> terrainSupports(StructureStart start) {
        return start.getPieces().stream().map(p -> {
            var b = p.getBoundingBox(); return new Foundation(b.minX(), b.minZ(), b.maxX(), b.maxZ(), b.minY());
        }).toList();
    }
    @Override public void afterPlace(WorldGenLevel level, StructureManager manager, ChunkGenerator generator, RandomSource random,
                                      BoundingBox clip, ChunkPos chunk, PiecesContainer pieces) {
        var b = pieces.pieces().getFirst().getBoundingBox();
        var marker = new BlockPos(b.minX(), b.minY() + 2, b.minZ());
        if (clip.isInside(marker)) level.setBlock(marker, Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
    }
    public static final class Piece extends StructurePiece {
        public Piece(BoundingBox box) { super(PIECE.get(), 0, box); }
        public Piece(StructurePieceSerializationContext context, CompoundTag tag) { super(PIECE.get(), tag); }
        @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {}
        @Override public void postProcess(WorldGenLevel level, StructureManager manager, ChunkGenerator generator,
                                          RandomSource random, BoundingBox clip, ChunkPos chunk, BlockPos pivot) {
            for (int x = boundingBox.minX(); x <= boundingBox.maxX(); x++)
                for (int z = boundingBox.minZ(); z <= boundingBox.maxZ(); z++) {
                    var pos = new BlockPos(x, boundingBox.minY(), z);
                    if (clip.isInside(pos)) level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
                }
        }
    }
}
