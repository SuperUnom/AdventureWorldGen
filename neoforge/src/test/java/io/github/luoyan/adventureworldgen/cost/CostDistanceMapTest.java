package io.github.luoyan.adventureworldgen.cost;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Node;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostDistanceMapTest {
    @Test
    void runsCompleteDijkstraAndConnectsNonGridPointsToSixteenNeighbors() {
        CostDistanceMap costs = flatMap();
        assertEquals(0, costs.nodeCost(new Node(0, 0)));
        assertEquals(16_000_000, costs.nodeCost(new Node(1, 0)));
        assertEquals(8_000_000, costs.costAt(new Vec2(8, 0)));
        assertEquals(CostDistanceMap.UNREACHABLE, costs.costAt(new Vec2(1000, 1000)));
    }

    @Test
    void coastMedianDefinesOneSharedSetOfLevelIntervals() {
        CostDistanceMap costs = flatMap();
        AdventureLevels levels = AdventureLevels.fromCoast(costs,
                List.of(new Vec2(16, 0), new Vec2(32, 0), new Vec2(0, 32), new Vec2(-16, 0)), 0.35);
        assertEquals(24_000_000.0, levels.coastReference());
        assertTrue(levels.contains(10, 24_000_000));
        assertFalse(levels.contains(0, 24_000_000));
    }

    @Test
    void compactGraphCachesBothDirectionsInOnePrimitiveSlot() {
        MacroTerrain terrain = (x, z) -> new MacroSample(x / 16.0, Double.NaN, WaterKind.NONE, false,
                "region/test", "plains", "terrain/test");
        var bounds = new CostDistanceMap.Bounds(-1, 1, -1, 1);
        var graph = new CompactGridCostGraph(bounds, 0, 0, 16,
                new EdgeCostCalculator(terrain, BoundaryIntersector.NONE, 8));
        var forward = graph.edge(new Node(0, 0), new Node(1, 0));
        var reverse = graph.edge(new Node(1, 0), new Node(0, 0));
        assertTrue(forward.passable());
        assertTrue(reverse.passable());
        assertEquals(1, graph.stats().computedPairs());
        assertEquals(1, graph.stats().hits());
        assertTrue(forward.micros() > reverse.micros());
    }

    private static CostDistanceMap flatMap() {
        MacroTerrain terrain = (x, z) -> new MacroSample(70, Double.NaN, WaterKind.NONE, false,
                "region/test", "plains", "terrain/test");
        EdgeCostCalculator calculator = new EdgeCostCalculator(terrain, BoundaryIntersector.NONE, 8);
        CompactGridCostGraph cache = new CompactGridCostGraph(
                new CostDistanceMap.Bounds(-2, 2, -2, 2), 0, 0, 16, calculator);
        return CostDistanceMap.build(new CostDistanceMap.Bounds(-2, 2, -2, 2), 0, 0, 16,
                node -> true, cache, calculator, new Vec2(0, 0),
                2_000_000, 1L << 30);
    }
}
