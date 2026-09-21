package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.plan.RoadPlan;
import java.util.*;

/** Rebuilt from frozen columns, independent of query order and cache warmth. */
final class RoadIndex {
    private final Map<Long, RoadPlan.Column> columns;
    private final Map<Long, List<RoadPlan.Column>> chunks;
    RoadIndex(RoadPlan plan) {
        var cells = new HashMap<Long, RoadPlan.Column>();
        var buckets = new HashMap<Long, List<RoadPlan.Column>>();
        for (var c : plan.columns()) {
            cells.put(RoadPlan.key(c.x(), c.z()), c);
            buckets.computeIfAbsent(RoadPlan.key(c.x() >> 4, c.z() >> 4), ignored -> new ArrayList<>()).add(c);
        }
        columns = Map.copyOf(cells);
        var frozen = new HashMap<Long, List<RoadPlan.Column>>();
        buckets.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        chunks = Map.copyOf(frozen);
    }
    RoadPlan.Column at(int x, int z) { return columns.get(RoadPlan.key(x, z)); }
    List<RoadPlan.Column> chunk(int x, int z) { return chunks.getOrDefault(RoadPlan.key(x, z), List.of()); }
}
