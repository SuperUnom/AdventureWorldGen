package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.server.level.ServerLevel;

/** Executes one runtime structure representation at a caller-supplied final position. */
@FunctionalInterface
public interface StructureAdapter {
    void generate(ServerLevel level, StructurePlacement placement);
}
