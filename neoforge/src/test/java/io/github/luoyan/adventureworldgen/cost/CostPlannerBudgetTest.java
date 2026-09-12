package io.github.luoyan.adventureworldgen.cost;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cost stage's two contracts that used to be implicit: how it fails, and how much memory its
 * refinement memos may retain.
 *
 * <p>Both budgets report through one shape ({@code RESOURCE_LIMIT} at stage {@code cost-graph}
 * with actual and limit), both are decided before anything is allocated, and neither the hit rate
 * nor thread interleaving may influence a returned cost.
 */
class CostPlannerBudgetTest {
    private static final Coastline SQUARE = new Coastline(List.of(
            new Vec2(-256, -256), new Vec2(256, -256), new Vec2(256, 256), new Vec2(-256, 256)));
    private static final MacroTerrain FLAT = (x, z) ->
            new MacroSample(80, Double.NaN, WaterKind.NONE, false, "r", "plains", "test");

    /** The node count this coastline produces at the profile's coarse spacing, derived the same way. */
    private static long nodeCountFor(Coastline coastline, PlannerProfile profile) {
        double radius = coastline.vertices().stream()
                .mapToDouble(p -> StrictMath.hypot(p.x(), p.z())).max().orElseThrow();
        long extent = (long) StrictMath.ceil(radius / profile.costGridSpacings().getFirst()) + 2L;
        long side = 2L * extent + 1L;
        return side * side;
    }

    @Test
    void nodeBudgetFailsAsAResourceLimitAtTheCostGraphStage() {
        var profile = PlannerProfile.V2.withBudgets(4, PlannerProfile.V2.maximumWorkingMemoryBytes());
        var failure = assertThrows(PlanningFailure.class,
                () -> new CostPlanner(profile).build(FLAT, SQUARE, new Vec2(0, 0)));
        assertEquals(PlanningFailure.Code.RESOURCE_LIMIT, failure.code());
        assertEquals("cost-graph", failure.stage());
        assertTrue(failure.diagnostics().containsKey("nodes"), failure.diagnostics().toString());
        assertTrue(failure.diagnostics().containsKey("maximum_nodes"), failure.diagnostics().toString());
    }

    @Test
    void memoryBudgetFailsAsTheSameResourceLimit() {
        // A node budget wide enough to pass, a working-memory budget far too small.
        var profile = PlannerProfile.V2.withBudgets(1_000_000, 1_024);
        var failure = assertThrows(PlanningFailure.class,
                () -> new CostPlanner(profile).build(FLAT, SQUARE, new Vec2(0, 0)));
        assertEquals(PlanningFailure.Code.RESOURCE_LIMIT, failure.code());
        assertEquals("cost-graph", failure.stage());
        assertTrue(failure.diagnostics().containsKey("estimated_bytes"), failure.diagnostics().toString());
        assertTrue(failure.diagnostics().containsKey("maximum_bytes"), failure.diagnostics().toString());
    }

    @Test
    void aNodeCountExactlyAtTheLimitIsLegal() {
        long nodes = nodeCountFor(SQUARE, PlannerProfile.V2);
        var profile = PlannerProfile.V2.withBudgets((int) nodes, PlannerProfile.V2.maximumWorkingMemoryBytes());
        var costs = new CostPlanner(profile).build(FLAT, SQUARE, new Vec2(0, 0));
        assertEquals(nodes, costs.nodeCount(), "the limit is inclusive: equal to it must succeed");
    }

    @Test
    void oneNodeBelowTheLimitAlreadyFails() {
        long nodes = nodeCountFor(SQUARE, PlannerProfile.V2);
        var profile = PlannerProfile.V2.withBudgets((int) (nodes - 1), PlannerProfile.V2.maximumWorkingMemoryBytes());
        var failure = assertThrows(PlanningFailure.class,
                () -> new CostPlanner(profile).build(FLAT, SQUARE, new Vec2(0, 0)));
        assertEquals(PlanningFailure.Code.RESOURCE_LIMIT, failure.code());
    }

    @Test
    void dimensionsThatWouldOverflowFailBeforeAnythingIsAllocated() {
        // A continent whose index space does not fit in a long: the guard must report a resource
        // limit rather than let an overflow reach array sizing.
        var enormous = new Coastline(List.of(
                new Vec2(-1e300, -1e300), new Vec2(1e300, -1e300),
                new Vec2(1e300, 1e300), new Vec2(-1e300, 1e300)));
        var failure = assertThrows(PlanningFailure.class,
                () -> new CostPlanner(PlannerProfile.V2).build(FLAT, enormous, new Vec2(0, 0)));
        assertEquals(PlanningFailure.Code.RESOURCE_LIMIT, failure.code());
        assertEquals("cost-graph", failure.stage());
    }

    @Test
    void aNonFiniteCoastlineIsAParameterErrorNotAResourceLimit() {
        // The coastline itself already refuses non-finite coordinates; whichever layer catches it,
        // a bad input must not be reported as an exhausted budget.
        var failure = assertThrows(IllegalArgumentException.class, () -> {
            var broken = new Coastline(List.of(
                    new Vec2(Double.NaN, 0), new Vec2(64, -64), new Vec2(64, 64)));
            new CostPlanner(PlannerProfile.V2).build(FLAT, broken, new Vec2(0, 0));
        });
        assertTrue(failure.getMessage().contains("finite"), failure.getMessage());
    }

    @Test
    void retainedRefinementStateStaysWithinItsDeclaredCaps() {
        // Two tiles of memo and four exact entries: querying far more distinct positions than that
        // must not grow the retained state beyond the caps.
        var costs = new CostPlanner(PlannerProfile.V2, 2 * CostRefinement.TILE_BYTES, 4)
                .build(FLAT, SQUARE, new Vec2(0, 0));
        assertEquals(2, costs.maximumRefinementTiles());
        assertEquals(4, costs.maximumExactCostEntries());
        for (int x = -600; x <= 600; x += 7) {
            for (int z = -600; z <= 600; z += 11) costs.refinedCostAt(x, z);
        }
        assertTrue(costs.retainedRefinementTiles() <= costs.maximumRefinementTiles(),
                "retained tiles: " + costs.retainedRefinementTiles());
        assertTrue(costs.retainedExactCostEntries() <= costs.maximumExactCostEntries(),
                "retained exact entries: " + costs.retainedExactCostEntries());
        assertTrue(costs.retainedExactCostEntries() > 0, "the cap must not evict everything");
    }

    @Test
    void coldWarmEvictedAndReorderedQueriesReturnTheSameCosts() throws Exception {
        // Tile boundaries and negative coordinates, including the exact 256-block tile edges.
        List<int[]> positions = new ArrayList<>();
        for (int x : new int[]{-513, -512, -511, -257, -256, -255, -129, -128, -1, 0, 1, 127, 128, 129, 255, 256, 383, 512, 513}) {
            for (int z : new int[]{-512, -255, -1, 0, 128, 256, 511}) positions.add(new int[]{x, z});
        }

        // A cold cache with room for everything: this is the reference.
        var reference = new CostPlanner(PlannerProfile.V2, 64L << 20, 1_000_000)
                .build(FLAT, SQUARE, new Vec2(0, 0));
        Map<String, Long> expected = new LinkedHashMap<>();
        for (int[] p : positions) expected.put(p[0] + "," + p[1], reference.refinedCostAt(p[0], p[1]));

        // Warm and evicted: two tiles of memo cannot hold the ~50 tiles these positions span, so the
        // second pass necessarily mixes hits, misses and recomputation.
        var bounded = new CostPlanner(PlannerProfile.V2, 2 * CostRefinement.TILE_BYTES, 4)
                .build(FLAT, SQUARE, new Vec2(0, 0));
        for (int[] p : positions) bounded.refinedCostAt(p[0], p[1]);
        for (int[] p : positions) {
            assertEquals(expected.get(p[0] + "," + p[1]), bounded.refinedCostAt(p[0], p[1]),
                    "evicted recomputation changed the cost at " + p[0] + "," + p[1]);
        }

        // Reverse order.
        var reversed = new CostPlanner(PlannerProfile.V2, 2 * CostRefinement.TILE_BYTES, 4)
                .build(FLAT, SQUARE, new Vec2(0, 0));
        List<int[]> backwards = new ArrayList<>(positions);
        java.util.Collections.reverse(backwards);
        for (int[] p : backwards) {
            assertEquals(expected.get(p[0] + "," + p[1]), reversed.refinedCostAt(p[0], p[1]),
                    "query order changed the cost at " + p[0] + "," + p[1]);
        }

        // Concurrent, with a memo too small to hold the working set: scheduling must not matter.
        var concurrent = new CostPlanner(PlannerProfile.V2, 3 * CostRefinement.TILE_BYTES, 8)
                .build(FLAT, SQUARE, new Vec2(0, 0));
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int[] p : positions) {
            tasks.add(() -> {
                assertEquals(expected.get(p[0] + "," + p[1]), concurrent.refinedCostAt(p[0], p[1]),
                        "concurrent query disagreed at " + p[0] + "," + p[1]);
                return null;
            });
        }
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (var result : pool.invokeAll(tasks)) result.get();
        }
        assertTrue(concurrent.retainedRefinementTiles() <= concurrent.maximumRefinementTiles());
    }

    @Test
    void allocatedSlotsAreCapacityNotEdges() {
        var costs = new CostPlanner(PlannerProfile.V2).build(FLAT, SQUARE, new Vec2(0, 0));
        var stats = costs.edgeStats();
        assertEquals(costs.nodeCount() * 4, stats.allocatedSlots(),
                "the compact graph reserves one slot per node per canonical direction");
        long side = (long) StrictMath.sqrt(costs.nodeCount());
        long theoretical = AdjacentEdgeCache.Stats.theoreticalPairs(side, side);
        assertTrue(theoretical < stats.allocatedSlots(),
                "in-bounds adjacent pairs must be fewer than the reserved slots on a bordered grid");
        // The Dijkstra pass fills most of the rectangle, but never more pairs than exist in bounds.
        assertTrue(stats.computedPairs() > 0, "the reachable pass computes pairs");
        assertTrue(stats.computedPairs() <= theoretical,
                "computed pairs must not exceed the in-bounds pair count: "
                        + stats.computedPairs() + " > " + theoretical);
    }

    @Test
    void aReverseLookupReusesThePairAndDoesNotCountItTwice() {
        var bounds = new CostDistanceMap.Bounds(0, 3, 0, 3);
        var calculator = new EdgeCostCalculator(FLAT, BoundaryIntersector.NONE, 8);
        var graph = new CompactGridCostGraph(bounds, 0, 0, 16, calculator);
        // An orthogonal pair owns exactly one canonical slot. (A diagonal one owns four, because
        // its two L-shaped paths are each checked as physical pairs.)
        var low = new AdjacentEdgeCache.Node(1, 1);
        var high = new AdjacentEdgeCache.Node(1, 2);
        graph.edge(low, high);
        assertEquals(1, graph.stats().computedPairs());
        graph.edge(high, low);
        assertEquals(1, graph.stats().computedPairs(), "the reverse direction reuses the canonical pair");
        assertEquals(1, graph.stats().hits());
        assertEquals(16 * 4, graph.stats().allocatedSlots());
    }
}
