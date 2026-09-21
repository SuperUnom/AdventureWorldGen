package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** A chunk-time executor receives resolved coordinates, never a plan or profile. */
public interface StructureExecutor {
    StructureStart generate(Structure structure, Structure.GenerationContext context, BlockPos anchor);
    static StructureStart fromStub(Structure structure, Structure.GenerationContext context, Structure.GenerationStub stub) {
        return new StructureStart(structure, context.chunkPos(), 0, stub.getPiecesBuilder().build());
    }
}
