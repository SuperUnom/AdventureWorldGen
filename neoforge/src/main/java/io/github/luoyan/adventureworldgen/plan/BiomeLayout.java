package io.github.luoyan.adventureworldgen.plan;

import java.util.List;

/**
 * The frozen layout section of a plan-v3 document: climate state, filler grid and the patches whose
 * ownership must survive boundary mixing.
 *
 * <p>This is the whole reflectively serialized {@code biome_layout} value, so its component names
 * and order are part of the persisted format. It carries data only: the planner rebuilds behaviour
 * from it, and the storage layer never needs the executable plan object to read or write it.
 */
public record BiomeLayout(ClimateState climate, FillerState filler, List<String> protectedPatches) {}
