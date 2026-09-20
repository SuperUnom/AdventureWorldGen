package io.github.luoyan.adventureworldgen.plan;

import java.util.Objects;

/** A planner-selected macro anchor, not a Minecraft StructureStart or final structure origin. */
public record PlannedStructurePlacement(
        String instanceId,
        ContentId structureId,
        int anchorX,
        int anchorZ
) {
    public PlannedStructurePlacement {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(structureId, "structureId");
    }
}
