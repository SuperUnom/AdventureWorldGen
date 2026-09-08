package io.github.luoyan.adventureworldgen.cost;

import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Node;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;

/** Builds the complete 16-block global cost field used before bounded 8-block candidate refinement. */
public final class CostPlanner {
    private static final int SAMPLE_CACHE_CAPACITY = 262_144;
    private final PlannerProfile profile;
    public CostPlanner(PlannerProfile profile) { this.profile = profile; }

    public Result build(MacroTerrain terrain, Coastline coastline, Vec2 spawn) {
        return build(terrain, coastline, spawn, ignored -> {});
    }

    public Result build(MacroTerrain terrain, Coastline coastline, Vec2 spawn, java.util.function.DoubleConsumer progress) {
        int spacing = profile.costGridSpacings().getFirst();
        double radius = coastline.vertices().stream().mapToDouble(point -> StrictMath.hypot(point.x(), point.z())).max().orElseThrow();
        int extent = (int) StrictMath.ceil(radius / spacing) + 2;
        var bounds = new CostDistanceMap.Bounds(-extent, extent, -extent, extent);
        if (bounds.nodeCount() > profile.maximumCostNodes())
            throw new IllegalArgumentException("cost bounds exceed configured maximum");
        var samples = new io.github.luoyan.adventureworldgen.runtime.ColumnQueryCache<io.github.luoyan.adventureworldgen.api.MacroSample>(SAMPLE_CACHE_CAPACITY);
        java.util.function.BiFunction<Integer,Integer,io.github.luoyan.adventureworldgen.api.MacroSample> query =
                (x,z) -> terrain.sample(x,z);
        MacroTerrain cachedTerrain = (x,z) -> {
            int ix = (int)x, iz = (int)z;
            // Reuse exact integer endpoints/midpoints. Diagonal fractions and boundary
            // intersections retain their original coordinates and integration precision.
            return x == ix && z == iz ? samples.get(ix,iz,query) : terrain.sample(x,z);
        };
        byte[] allowed = new byte[Math.toIntExact(bounds.nodeCount())];
        for (int gx = -extent; gx <= extent; gx++) for (int gz = -extent; gz <= extent; gz++) {
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
        long retainedBytes = CompactGridCostGraph.retainedBytes(bounds.nodeCount())
                + Math.multiplyExact(bounds.nodeCount(), Long.BYTES + 2L * Integer.BYTES + 2L)
                + SAMPLE_CACHE_CAPACITY * 192L;
        if (retainedBytes > profile.maximumWorkingMemoryBytes())
            throw new io.github.luoyan.adventureworldgen.planner.PlanningFailure(
                    io.github.luoyan.adventureworldgen.planner.PlanningFailure.Code.RESOURCE_LIMIT, "cost-graph",
                    "compact cost graph exceeds planner-v2 memory budget",
                    java.util.Map.of("estimated_bytes", retainedBytes,
                            "maximum_bytes", profile.maximumWorkingMemoryBytes()));
        var cache = new CompactGridCostGraph(bounds, 0, 0, spacing, calculator);
        CostDistanceMap distances = CostDistanceMap.build(bounds, 0, 0, spacing, predicate,
                cache, calculator, spawn, profile.maximumCostNodes(), profile.maximumWorkingMemoryBytes(),
                fraction -> progress.accept(0.15 + fraction * 0.8));
        AdventureLevels levels = AdventureLevels.fromCoast(distances,
                coastline.equalArcSamples(profile.coast().targetArcSampleSpacing(), profile.coast().maximumArcSamples()),
                profile.levelTolerance());
        progress.accept(1);
        return new Result(distances, levels, cache.stats(), spacing, bounds.nodeCount(), terrain, coastBoundaries, new java.util.concurrent.ConcurrentHashMap<>(), new java.util.concurrent.ConcurrentHashMap<>());
    }

    public record Result(CostDistanceMap distances, AdventureLevels levels, AdjacentEdgeCache.Stats edgeStats,
                         int spacing, long nodeCount, MacroTerrain terrain, BoundaryIntersector boundaries,
                         java.util.Map<Long, long[]> refinements, java.util.Map<Long,Long> exactCosts) {
        public boolean accepts(int level, int x, int z) { return levels.contains(level, refinedCostAt(x, z)); }

        public double normalizedPreferenceAt(int x,int z,double radius) {
            long value=distances.nodeCost(new Node(Math.floorDiv(x,spacing),Math.floorDiv(z,spacing)));
            return value==CostDistanceMap.UNREACHABLE?12+10*StrictMath.hypot(x,z)/radius:10*value/levels.coastReference();
        }

        /** A deterministic 256-square 8-grid seeded at coarse nodes and its perimeter from the complete 16-grid. */
        public long refinedCostAt(int x,int z) {
            long key=((long)x<<32)^(z&0xffffffffL);
            return exactCosts.computeIfAbsent(key,ignored->computeRefinedCostAt(x,z));
        }
        private long computeRefinedCostAt(int x, int z) {
            final int fine = 8, half = 128;
            int originX = Math.floorDiv(x + half, 256) * 256 - half;
            int originZ = Math.floorDiv(z + half, 256) * 256 - half;
            int width = 33;
            long key = ((long) originX << 32) ^ (originZ & 0xffffffffL);
            long[] cost = refinements.computeIfAbsent(key, ignored -> refine(originX, originZ));
            var calculator = new EdgeCostCalculator(terrain, boundaries, fine);
            long best = CostDistanceMap.UNREACHABLE;
            int baseX = Math.floorDiv(x - originX, fine), baseZ = Math.floorDiv(z - originZ, fine);
            for (int gx = StrictMath.max(0, baseX - 1); gx <= StrictMath.min(width - 1, baseX + 2); gx++)
                for (int gz = StrictMath.max(0, baseZ - 1); gz <= StrictMath.min(width - 1, baseZ + 2); gz++) {
                    long base = cost[gx * width + gz];
                    if (base == CostDistanceMap.UNREACHABLE) continue;
                    int wx = originX + gx * fine, wz = originZ + gz * fine;
                    if (wx == x && wz == z) best = StrictMath.min(best, base);
                    else {
                        EdgeCost edge = calculator.calculate(wx, wz, x, z);
                        if (edge.passable() && base <= Long.MAX_VALUE - edge.forwardMicros())
                            best = StrictMath.min(best, base + edge.forwardMicros());
                    }
                }
            return best;
        }
        private long[] refine(int originX, int originZ) {
            final int fine = 8, width = 33, count = width * width;
            long[] cost = new long[count];
            java.util.Arrays.fill(cost, CostDistanceMap.UNREACHABLE);
            record Entry(long cost, int gx, int gz) {}
            java.util.PriorityQueue<Entry> queue = new java.util.PriorityQueue<>(java.util.Comparator
                    .comparingLong(Entry::cost).thenComparingInt(Entry::gx).thenComparingInt(Entry::gz));
            for (int gx = 0; gx < width; gx++) for (int gz = 0; gz < width; gz++) {
                int wx = originX + gx * fine, wz = originZ + gz * fine;
                boolean coarseNode = Math.floorMod(wx, spacing) == 0 && Math.floorMod(wz, spacing) == 0;
                if (!coarseNode && gx != 0 && gz != 0 && gx != width - 1 && gz != width - 1) continue;
                // Keep interior coarse costs (especially the source) as valid upper bounds.
                // Seeding only the perimeter invents a detour out of and back into the source tile.
                long seeded = coarseNode ? distances.nodeCost(new Node(Math.floorDiv(wx, spacing), Math.floorDiv(wz, spacing)))
                        : distances.costAt(new Vec2(wx, wz));
                if (seeded != CostDistanceMap.UNREACHABLE) {
                    cost[gx * width + gz] = seeded;
                    queue.add(new Entry(seeded, gx, gz));
                }
            }
            var calculator = new EdgeCostCalculator(terrain, boundaries, fine);
            var edgeCache = new AdjacentEdgeCache("terrain-v2+" + PlannerProfile.V2.hydrologyVersion(),
                    PlannerProfile.V2.algorithmVersion() + "/cost-8", originX, originZ, fine, calculator);
            var graph = new GridCostGraph(edgeCache);
            int[] delta = {-1, 0, 1};
            boolean[] settled = new boolean[count];
            while (!queue.isEmpty()) {
                Entry current = queue.remove();
                int currentIndex = current.gx * width + current.gz;
                if (settled[currentIndex] || cost[currentIndex] != current.cost) continue;
                settled[currentIndex] = true;
                for (int dx : delta) for (int dz : delta) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = current.gx + dx, nz = current.gz + dz;
                    if (nx < 0 || nz < 0 || nx >= width || nz >= width) continue;
                    int nextIndex = nx * width + nz;
                    if (settled[nextIndex]) continue;
                    var edge = graph.edge(new Node(current.gx, current.gz), new Node(nx, nz));
                    if (!edge.passable() || current.cost > Long.MAX_VALUE - edge.micros()) continue;
                    long candidate = current.cost + edge.micros();
                    if (candidate < cost[nextIndex]) { cost[nextIndex] = candidate; queue.add(new Entry(candidate, nx, nz)); }
                }
            }
            return cost;
        }

    }
}
