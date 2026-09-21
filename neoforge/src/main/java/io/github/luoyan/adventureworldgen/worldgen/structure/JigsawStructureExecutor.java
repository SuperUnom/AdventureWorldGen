package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;

/** Native pool assembly at the supplied anchor, without post-assembly translation. */
public final class JigsawStructureExecutor implements StructureExecutor {
    @Override public StructureStart generate(Structure structure, Structure.GenerationContext c, BlockPos anchor) {
        var p = (JigsawParameters) structure;
        int y = p.adventureworldgen$startHeight().sample(c.random(), new WorldGenerationContext(c.chunkGenerator(), c.heightAccessor()));
        var origin = new BlockPos(anchor.getX(), y, anchor.getZ());
        var stub = JigsawPlacement.addPieces(c, p.adventureworldgen$startPool(), p.adventureworldgen$startJigsawName(),
                p.adventureworldgen$maxDepth(), origin, p.adventureworldgen$useExpansionHack(),
                p.adventureworldgen$projectStartToHeightmap(), p.adventureworldgen$maxDistanceFromCenter(),
                PoolAliasLookup.create(p.adventureworldgen$poolAliases(), origin, c.seed()),
                p.adventureworldgen$dimensionPadding(), p.adventureworldgen$liquidSettings());
        if (stub.isEmpty()) return StructureStart.INVALID_START;
        BlockPos pos = stub.get().position();
        if (!c.validBiome().test(c.biomeSource().getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2,
                c.randomState().sampler()))) return StructureStart.INVALID_START;
        return StructureExecutor.fromStub(structure, c, stub.get());
    }
}
