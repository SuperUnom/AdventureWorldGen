package io.github.luoyan.adventureworldgen.cost;

import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Directed;
import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Node;

import java.util.concurrent.ConcurrentHashMap;

/** Adds planner-v2 diagonal double-corner checks on top of physical segment costs. */
public final class GridCostGraph implements CostGraph {
    private final AdjacentEdgeCache segments;
    private final ConcurrentHashMap<DirectedKey, Directed> diagonals = new ConcurrentHashMap<>();

    public GridCostGraph(AdjacentEdgeCache segments) { this.segments = segments; }

    @Override public Directed edge(Node from, Node to) {
        int dx = StrictMath.abs(from.gridX() - to.gridX());
        int dz = StrictMath.abs(from.gridZ() - to.gridZ());
        if (dx != 1 || dz != 1) return segments.edge(from, to);
        return diagonals.computeIfAbsent(new DirectedKey(from, to), ignored -> diagonal(from, to));
    }

    private Directed diagonal(Node from, Node to) {
        Node firstCorner = new Node(to.gridX(), from.gridZ());
        Node secondCorner = new Node(from.gridX(), to.gridZ());
        if (!segments.edge(from, firstCorner).passable() || !segments.edge(firstCorner, to).passable()
                || !segments.edge(from, secondCorner).passable() || !segments.edge(secondCorner, to).passable())
            return Directed.BLOCKED;
        return segments.edge(from, to);
    }

    private record DirectedKey(Node from, Node to) {}
}
