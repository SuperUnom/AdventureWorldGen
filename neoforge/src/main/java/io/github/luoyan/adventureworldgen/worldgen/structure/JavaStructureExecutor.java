package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Preserve native origin, validation, piece types, afterPlace and load callbacks. Never translate pieces. */
public final class JavaStructureExecutor implements StructureExecutor {
    @Override public StructureStart generate(Structure structure, Structure.GenerationContext c, BlockPos anchor) {
        return structure.generate(c.registryAccess(), c.chunkGenerator(), c.biomeSource(), c.randomState(),
                c.structureTemplateManager(), c.seed(), c.chunkPos(), 0, c.heightAccessor(), c.validBiome());
    }
}
