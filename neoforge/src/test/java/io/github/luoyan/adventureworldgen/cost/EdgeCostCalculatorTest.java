package io.github.luoyan.adventureworldgen.cost;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Node;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeCostCalculatorTest {
    @Test
    void matchesPlannerV2DirectedGoldenVectors() {
        assertCost(x -> 0.0, 16_000_000, 16_000_000);
        assertCost(x -> x <= 8 ? x / 2.0 : (16 - x) / 2.0, 64_000_000, 64_000_000);
        assertCost(x -> x / 2.0, 72_000_000, 56_000_000);
    }

    @Test
    void blocksOceanAndCachesBothDirectionsWithOnePhysicalCalculation() {
        AtomicInteger calls = new AtomicInteger();
        MacroTerrain ocean = (x, z) -> {
            calls.incrementAndGet();
            return sample(0, x >= 8 ? WaterKind.OCEAN : WaterKind.NONE, x >= 8 ? 64 : Double.NaN);
        };
        var cache = new AdjacentEdgeCache("terrain/1", "cost/1", 0, 0, 16,
                new EdgeCostCalculator(ocean, BoundaryIntersector.NONE, 8));
        assertFalse(cache.edge(new Node(0, 0), new Node(1, 0)).passable());
        int afterFirst = calls.get();
        assertFalse(cache.edge(new Node(1, 0), new Node(0, 0)).passable());
        assertEquals(afterFirst, calls.get());
        assertEquals(1, cache.stats().computedPairs());
        assertEquals(1, cache.stats().hits());
    }

    @Test
    void diagonalRequiresBothOrthogonalCornerRoutes() {
        MacroTerrain terrain = (x, z) -> sample((x == 16 && z == 0) ? 100 : 0, WaterKind.NONE, Double.NaN);
        var cache = new AdjacentEdgeCache("terrain/1", "cost/1", 0, 0, 16,
                new EdgeCostCalculator(terrain, BoundaryIntersector.NONE, 8));
        var graph = new GridCostGraph(cache);
        assertFalse(graph.edge(new Node(0, 0), new Node(1, 1)).passable());
    }

    private static void assertCost(java.util.function.DoubleUnaryOperator heights, long forward, long reverse) {
        MacroTerrain terrain = (x, z) -> sample(heights.applyAsDouble(x), WaterKind.NONE, Double.NaN);
        EdgeCost edge = new EdgeCostCalculator(terrain, BoundaryIntersector.NONE, 8).calculate(0, 0, 16, 0);
        assertTrue(edge.passable());
        assertEquals(forward, edge.forwardMicros());
        assertEquals(reverse, edge.reverseMicros());
    }

    private static MacroSample sample(double height, WaterKind water, double surface) {
        return new MacroSample(height, surface, water, false, "region/test", "plains", "terrain/test");
    }
}
