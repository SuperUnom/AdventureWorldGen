package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

public final class TemplateStructureExecutor implements StructureExecutor {
    @Override public StructureStart generate(Structure structure, Structure.GenerationContext context, BlockPos anchor) {
        var stub = ((TemplateStructure) structure).assemble(context, anchor);
        var pos = stub.position();
        if (!context.validBiome().test(context.biomeSource().getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2,
                context.randomState().sampler()))) return StructureStart.INVALID_START;
        return StructureExecutor.fromStub(structure, context, stub);
    }
}
