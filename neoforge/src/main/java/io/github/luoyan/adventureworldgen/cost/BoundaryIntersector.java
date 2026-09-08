package io.github.luoyan.adventureworldgen.cost;

import java.util.List;

/** Supplies exact normalized segment parameters where known water, coast, or hazard boundaries cross. */
@FunctionalInterface
public interface BoundaryIntersector {
    BoundaryIntersector NONE = (x0, z0, x1, z1) -> List.of();
    List<Double> intersections(double x0, double z0, double x1, double z1);
}
