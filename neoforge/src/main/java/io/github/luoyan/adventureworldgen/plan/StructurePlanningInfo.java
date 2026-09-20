package io.github.luoyan.adventureworldgen.plan;

import java.util.Objects;

/** Pure, Minecraft-independent facts a structure type exposes to the planner. */
public record StructurePlanningInfo(ContentId structureId) {
    public StructurePlanningInfo {
        Objects.requireNonNull(structureId, "structureId");
    }
}
