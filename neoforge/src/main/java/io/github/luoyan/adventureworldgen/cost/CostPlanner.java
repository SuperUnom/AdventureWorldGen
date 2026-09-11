package io.github.luoyan.adventureworldgen.cost;

import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Node;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/** Builds the complete 16-block global cost field used before bounded 8-block candidate refinement. */
public final class CostPlanner {
    private static final int SAMPLE_CACHE_CAPACITY = 262_144;
    /**
     * Working memory the 8-block refinement memos may retain, charged in bytes against the same
     * budget as the coarse graph instead of growing without limit. One tile is 33x33 longs.
     */
    private static final long REFINEMENT_BYTE_BUDGET = 64L << 20;
    /** Entry cap for the exact-cost memo, charged at {@link CostRefinement#EXACT_ENTRY_BYTES}. */
    private static final int MAXIMUM_EXACT_COST_ENTRIES = 1_000_000;
    private final PlannerProfile profile;
    private final long refinementByteBudget;
    private final int maximumExactCostEntries;

    public CostPlanner(PlannerProfile profile) {
        this(profile, REFINEMENT_BYTE_BUDGET, MAXIMUM_EXACT_COST_ENTRIES);
    }

    /**
     * Budget override for tests that need to observe eviction without generating thousands of real
     * 256-block tiles. The defaults above are what production uses.
     */
    CostPlanner(PlannerProfile profile, long refinementByteBudget, int maximumExactCostEntries) {
        this.profile = profile;
        this.refinementByteBudget = refinementByteBudget;
        this.maximumExactCostEntries = maximumExactCostEntries;
    }

    public Result build(MacroTerrain terrain, Coastline coastline, Vec2 spawn) {
        return build(terrain, coastline, spawn, ignored -> {});
    }

    public Result build(MacroTerrain terrain, Coastline coastline, Vec2 spawn, java.util.function.DoubleConsumer progress) {
        int spacing = profile.costGridSpacings().getFirst();
        double radius = coastline.vertices().stream().mapToDouble(point -> StrictMath.hypot(point.x(), point.z())).max().orElseThrow();
        // Every size is derived in overflow-checked long arithmetic and both budgets are checked
        // before a single array is allocated. An oversized continent or a tight budget therefore
        // fails as an explicit resource limit - the same failure code and stage as any other
        // planner resource limit - instead of an ArithmeticException, a NegativeArraySizeException
        // or an OutOfMemoryError partway through the graph.
        if (!(radius > 0) || !Double.isFinite(radius))
            // A bad coastline is a caller error, not a resource limit: the two must stay
            // distinguishable so an automation layer never reads "budget exhausted" for a bad input.
            throw new IllegalArgumentException("coastline must have a finite positive extent");
        long nodes, extent;
        try {
            extent = Math.addExact((long) StrictMath.ceil(radius / spacing), 2L);
            long side = Math.addExact(Math.multiplyExact(extent, 2L), 1L);
            nodes = Math.multiplyExact(side, side);
        } catch (ArithmeticException overflow) {
            // A continent whose index space does not fit in a long is still a resource limit, and
            // it is decided here, before any array is sized from it.
            throw resourceLimit("cost bounds do not fit in the planner's index space",
                    java.util.Map.of("radius", radius, "spacing", (long) spacing));
        }
        if (nodes > profile.maximumCostNodes())
            throw resourceLimit("cost bounds exceed the configured node budget",
                    java.util.Map.of("nodes", nodes, "maximum_nodes", (long) profile.maximumCostNodes()));
        long retainedBytes = Math.addExact(
                Math.addExact(
                        Math.addExact(CompactGridCostGraph.retainedBytes(nodes),
                                Math.multiplyExact(nodes, Long.BYTES + 2L * Integer.BYTES + 2L)),
                        Math.multiplyExact((long) SAMPLE_CACHE_CAPACITY, 192L)),
                Math.addExact(refinementByteBudget,
                        Math.multiplyExact((long) maximumExactCostEntries, CostRefinement.EXACT_ENTRY_BYTES)));
        if (retainedBytes > profile.maximumWorkingMemoryBytes())
            throw resourceLimit("compact cost graph exceeds the planner-v2 working memory budget",
                    java.util.Map.of("estimated_bytes", retainedBytes,
                            "maximum_bytes", profile.maximumWorkingMemoryBytes()));
        int extentInt = Math.toIntExact(extent);
        var bounds = new CostDistanceMap.Bounds(-extentInt, extentInt, -extentInt, extentInt);
        var samples = new io.github.luoyan.adventureworldgen.spatial.ColumnQueryCache<io.github.luoyan.adventureworldgen.api.MacroSample>(SAMPLE_CACHE_CAPACITY);
        java.util.function.BiFunction<Integer,Integer,io.github.luoyan.adventureworldgen.api.MacroSample> query =
                (x,z) -> terrain.sample(x,z);
        MacroTerrain cachedTerrain = (x,z) -> {
            int ix = (int)x, iz = (int)z;
            // Reuse exact integer endpoints/midpoints. Diagonal fractions and boundary
            // intersections retain their original coordinates and integration precision.
            return x == ix && z == iz ? samples.get(ix,iz,query) : terrain.sample(x,z);
        };
        byte[] allowed = new byte[Math.toIntExact(nodes)];
        for (int gx = -extentInt; gx <= extentInt; gx++) for (int gz = -extentInt; gz <= extentInt; gz++) {
            var sample = cachedTerrain.sample(gx * (double) spacing, gz * (double) spacing);
            allowed[bounds.index(new Node(gx, gz))] = (byte) (sample.waterKind() != WaterKind.OCEAN
                    && sample.waterKind() != WaterKind.LAVA && !sample.hazardous() ? 1 : 0);
        }
        progress.accept(0.15);
        java.util.function.Predicate<Node> predicate = node -> allowed[bounds.index(node)] != 0;
        BoundaryIntersector coastBoundaries = (x0, z0, x1, z1) -> {
            double a = coastline.signedDistance(x0, z0), b = coastline.signedDistance(x1, z1);
            if (a == 0.0) return java.util.List.of(0.0);
            if (b == 0.0) return java.util.List.of(1.0);
            if (a * b > 0.0) return java.util.List.of();
            double low = 0.0, high = 1.0;
            for (int i = 0; i < 48; i++) {
                double middle = (low + high) * 0.5;
                double value = coastline.signedDistance(x0 + (x1 - x0) * middle, z0 + (z1 - z0) * middle);
                if ((value >= 0.0) == (a >= 0.0)) low = middle; else high = middle;
            }
            return java.util.List.of((low + high) * 0.5);
        };
        var calculator = new EdgeCostCalculator(cachedTerrain, coastBoundaries, profile.costEdgeSampleSpacing());
        var cache = new CompactGridCostGraph(bounds, 0, 0, spacing, calculator);
        CostDistanceMap distances = CostDistanceMap.build(bounds, 0, 0, spacing, predicate,
                cache, calculator, spawn, profile.maximumCostNodes(), profile.maximumWorkingMemoryBytes(),
                fraction -> progress.accept(0.15 + fraction * 0.8));
        AdventureLevels levels = AdventureLevels.fromCoast(distances,
                coastline.equalArcSamples(profile.coast().targetArcSampleSpacing(), profile.coast().maximumArcSamples()),
                profile.levelTolerance());
        progress.accept(1);
        return new Result(distances, levels, cache.stats(), spacing, bounds.nodeCount(), terrain, coastBoundaries,
                new CostRefinement(profile, terrain, coastBoundaries, spacing, distances,
                        refinementByteBudget, maximumExactCostEntries));
    }

    /** The one resource-limit shape this stage reports: same code, same stage, actual and limit. */
    private static PlanningFailure resourceLimit(String reason, java.util.Map<String, ?> details) {
        return new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, FailureStage.COST_GRAPH, reason, details);
    }

    public record Result(CostDistanceMap distances, AdventureLevels levels, AdjacentEdgeCache.Stats edgeStats,
                         int spacing, long nodeCount, MacroTerrain terrain, BoundaryIntersector boundaries,
                         CostRefinement refinement) {

        /**
         * The production adventure-level signal: coarse-grid node cost, normalized against the coast
         * median. {@code JointPlanner.LevelConstraint.penalty} consumes this to order candidates.
         *
         * <p>Nothing here rejects a position. There used to be an {@code accepts(level, x, z)} helper
         * built on the 8-block refined cost, which no production caller ever used - level 0 is the
         * only level the coast interval brackets at the origin, so it could not have expressed the
         * author model's soft preference anyway. Coarse cost is the admission basis; refined cost is
         * a diagnostic that refines a single position and must not become a level gate.
         */
        public double normalizedPreferenceAt(int x,int z,double radius) {
            long value=distances.nodeCost(new Node(Math.floorDiv(x,spacing),Math.floorDiv(z,spacing)));
            return value==CostDistanceMap.UNREACHABLE?12+10*StrictMath.hypot(x,z)/radius:10*value/levels.coastReference();
        }

        /**
         * A deterministic 256-square 8-grid seeded at coarse nodes and its perimeter from the
         * complete 16-grid.
         *
         * <p>The memo behind it is bounded ({@link CostRefinement}), so an evicted entry is
         * recomputed rather than retained forever. Because every value is a pure function of its
         * key, a cold, warm or partially evicted cache returns the same cost, and planning never
         * depends on the hit rate.
         */
        public long refinedCostAt(int x,int z) { return refinement.costAt(x, z); }

        /** Retained refinement tiles, exact-cost entries and their declared caps, for tests. */
        public int retainedRefinementTiles() { return refinement.retainedTiles(); }

        public int retainedExactCostEntries() { return refinement.retainedExactEntries(); }

        public int maximumRefinementTiles() { return refinement.maximumTiles(); }

        public int maximumExactCostEntries() { return refinement.maximumExactEntries(); }
    }
}
