package io.github.luoyan.adventureworldgen.cost;

import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Node;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.spatial.Vec2;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The 8-block refinement behind {@code CostPlanner.Result.refinedCostAt}, with the two result
 * memos explicitly bounded.
 *
 * <p>The coarse pass avoids cache growth by construction: {@code CompactGridCostGraph} owns one
 * fixed slot per node and direction. The refinement path cannot do that - its input is an
 * unbounded stream of queried coordinates - so both memos carry a declared capacity and evict on
 * insertion:
 *
 * <ul>
 *   <li>refinement tiles, charged in bytes, one {@code 33 x 33} long array per 256-block tile;</li>
 *   <li>exact costs, charged in entries.</li>
 * </ul>
 *
 * <p>Both quotas are allocated from the same {@link PlannerProfile#maximumWorkingMemoryBytes()}
 * budget the cost graph is checked against, and count towards the graph's estimate, so a profile
 * with a tight budget cannot silently grow an unbounded memo beside it.
 *
 * <p><strong>Eviction can only cause recomputation.</strong> Every value is a pure function of its
 * key (the frozen terrain plus the calculator's own parameters), a value is published only after it
 * is complete, and nothing reads a partially computed tile. A cold cache, a fully warm cache and a
 * cache that just evicted therefore return identical costs, which is also why planning never
 * depends on the hit rate or on how concurrent queries happened to interleave - only on the
 * declared operation budgets.
 */
final class CostRefinement {
    /** One refinement tile: a 33x33 grid of 8-block costs. */
    static final int TILE_SIDE = 33;
    static final long TILE_BYTES = (long) TILE_SIDE * TILE_SIDE * Long.BYTES;
    /** Roughly what one boxed entry costs in a ConcurrentHashMap before table overhead. */
    static final long EXACT_ENTRY_BYTES = 80L;

    private final PlannerProfile profile;
    private final MacroTerrain terrain;
    private final BoundaryIntersector boundaries;
    private final int spacing;
    private final CostDistanceMap distances;
    private final BoundedCache<Long, long[]> refinements;
    private final BoundedCache<Long, Long> exactCosts;
    private final AtomicLong terrainsSampled = new AtomicLong();

    CostRefinement(PlannerProfile profile, MacroTerrain terrain, BoundaryIntersector boundaries, int spacing,
                   CostDistanceMap distances, long refinementByteBudget, int exactEntryBudget) {
        this.profile = profile;
        this.terrain = terrain;
        this.boundaries = boundaries;
        this.spacing = spacing;
        this.distances = distances;
        int tiles = (int) Math.max(1, refinementByteBudget / TILE_BYTES);
        this.refinements = new BoundedCache<>(tiles);
        this.exactCosts = new BoundedCache<>(Math.max(1, exactEntryBudget));
    }

    /** The exact 8-block cost of one position; identical whatever the cache state is. */
    long costAt(int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        return exactCosts.get(key, ignored -> compute(x, z));
    }

    /** Refinement tiles currently retained, for tests and diagnostics. */
    int retainedTiles() { return refinements.size(); }

    /** Exact-cost entries currently retained, for tests and diagnostics. */
    int retainedExactEntries() { return exactCosts.size(); }

    int maximumTiles() { return refinements.capacity(); }

    int maximumExactEntries() { return exactCosts.capacity(); }

    long exactCacheBytes() { return (long) exactCosts.capacity() * EXACT_ENTRY_BYTES; }

    long tileCacheBytes() { return (long) refinements.capacity() * TILE_BYTES; }

    /** Terrain samples taken by refinement so far; a cache miss shows up here as extra work. */
    long terrainSamples() { return terrainsSampled.get(); }

    private long compute(int x, int z) {
        final int fine = 8, half = 128;
        int originX = Math.floorDiv(x + half, 256) * 256 - half;
        int originZ = Math.floorDiv(z + half, 256) * 256 - half;
        long key = ((long) originX << 32) ^ (originZ & 0xffffffffL);
        long[] cost = refinements.get(key, ignored -> refine(originX, originZ));
        var calculator = new EdgeCostCalculator(terrain, boundaries, fine);
        long best = CostDistanceMap.UNREACHABLE;
        int baseX = Math.floorDiv(x - originX, fine), baseZ = Math.floorDiv(z - originZ, fine);
        for (int gx = StrictMath.max(0, baseX - 1); gx <= StrictMath.min(TILE_SIDE - 1, baseX + 2); gx++)
            for (int gz = StrictMath.max(0, baseZ - 1); gz <= StrictMath.min(TILE_SIDE - 1, baseZ + 2); gz++) {
                long base = cost[gx * TILE_SIDE + gz];
                if (base == CostDistanceMap.UNREACHABLE) continue;
                int wx = originX + gx * fine, wz = originZ + gz * fine;
                if (wx == x && wz == z) best = StrictMath.min(best, base);
                else {
                    terrainsSampled.incrementAndGet();
                    EdgeCost edge = calculator.calculate(wx, wz, x, z);
                    if (edge.passable() && base <= Long.MAX_VALUE - edge.forwardMicros())
                        best = StrictMath.min(best, base + edge.forwardMicros());
                }
            }
        return best;
    }

    private long[] refine(int originX, int originZ) {
        final int fine = 8, width = TILE_SIDE, count = width * width;
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
        // The refinement's edge-cache version comes from the same profile as the coarse graph, so
        // first planning and a READY reload cannot label the same parameters differently.
        var edgeCache = new AdjacentEdgeCache("terrain-v2+" + profile.hydrologyVersion(),
                profile.algorithmVersion() + "/cost-8", originX, originZ, fine, calculator);
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
                terrainsSampled.incrementAndGet();
                var edge = graph.edge(new Node(current.gx, current.gz), new Node(nx, nz));
                if (!edge.passable() || current.cost > Long.MAX_VALUE - edge.micros()) continue;
                long candidate = current.cost + edge.micros();
                if (candidate < cost[nextIndex]) { cost[nextIndex] = candidate; queue.add(new Entry(candidate, nx, nz)); }
            }
        }
        return cost;
    }

    /**
     * Insertion-ordered, size-bounded memo. A value is computed outside the map and published with
     * {@code putIfAbsent}, so two threads racing on the same key both compute the same pure value
     * and one of them wins; no reader can observe a half-written entry. Eviction removes the oldest
     * keys only, and a removed key is simply recomputed on its next query.
     */
    private static final class BoundedCache<K, V> {
        private final int capacity;
        private final ConcurrentHashMap<K, V> values = new ConcurrentHashMap<>();
        private final Queue<K> insertionOrder = new ConcurrentLinkedQueue<>();

        BoundedCache(int capacity) { this.capacity = capacity; }

        V get(K key, Function<K, V> loader) {
            V existing = values.get(key);
            if (existing != null) return existing;
            V loaded = loader.apply(key);
            V raced = values.putIfAbsent(key, loaded);
            if (raced != null) return raced;
            insertionOrder.add(key);
            evict();
            return loaded;
        }

        private void evict() {
            while (values.size() > capacity) {
                K oldest = insertionOrder.poll();
                if (oldest == null) return;
                values.remove(oldest);
            }
        }

        int size() { return values.size(); }

        int capacity() { return capacity; }
    }
}
