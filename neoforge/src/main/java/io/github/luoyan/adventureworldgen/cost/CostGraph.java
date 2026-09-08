package io.github.luoyan.adventureworldgen.cost;

import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Directed;
import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Node;

/** Directed neighbor costs for the complete planner grid. */
public interface CostGraph {
    Directed edge(Node from, Node to);
}
