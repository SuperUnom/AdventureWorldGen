package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.plan.PlannedStructurePlacement;
import io.github.luoyan.adventureworldgen.plan.StructurePlanningCatalog;
import java.util.List;

/** Startup integration boundary: only pure anchors and horizontal facts leave the preparation stage. */
@FunctionalInterface
public interface StructurePreparation {
    StructurePreparation DECLARED = (view, catalog) -> new Result(view.plannedStructures(), catalog);
    Result prepare(GeneratedAdventurePlan naturalView, StructurePlanningCatalog catalog);

    record Result(List<PlannedStructurePlacement> placements, StructurePlanningCatalog catalog) {
        public Result { placements = List.copyOf(placements); }
    }
}
