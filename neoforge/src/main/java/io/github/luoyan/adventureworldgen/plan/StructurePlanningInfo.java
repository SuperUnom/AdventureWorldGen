package io.github.luoyan.adventureworldgen.plan;

import java.util.Objects;

/** Pure, Minecraft-independent facts a structure type exposes to the planner. */
public record StructurePlanningInfo(ContentId structureId, RoadAccess roadAccess) {
    public StructurePlanningInfo(ContentId id) { this(id, null); }
    public record RoadAccess(int exclusionRadius, int approachDistance) {
        public RoadAccess {
            if (exclusionRadius < 1 || exclusionRadius > 128 || approachDistance < exclusionRadius + 8 || approachDistance > 256)
                throw new IllegalArgumentException("invalid structure road access envelope");
        }
    }
    public StructurePlanningInfo {
        Objects.requireNonNull(structureId, "structureId");
    }
}
