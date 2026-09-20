package io.github.luoyan.adventureworldgen.api;

import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlannedStructurePlacement;

import java.util.List;

/** Immutable runtime-facing projection of a validated current-plan snapshot. */
public interface AdventurePlanView {
    ContentId biomeAt(int blockX, int blockY, int blockZ);
    MacroSample terrainAt(double blockX, double blockZ);
    List<PlannedStructurePlacement> plannedStructures();
    SpawnPosition spawnPosition();

    record SpawnPosition(double x, double y, double z, float yaw) {}
}
