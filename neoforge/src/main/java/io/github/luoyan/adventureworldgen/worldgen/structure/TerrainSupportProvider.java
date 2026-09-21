package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.world.level.levelgen.structure.StructureStart;
import java.util.List;

/** Opt-in Java structure capability: support coordinates must be final before NOISE begins. */
public interface TerrainSupportProvider {
    List<Foundation> terrainSupports(StructureStart start);
}
