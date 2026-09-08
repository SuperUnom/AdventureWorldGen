package io.github.luoyan.adventureworldgen.cost;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Concurrent compute-once cache for canonical adjacent-node pairs and both directed values. */
public final class AdjacentEdgeCache {
    private final String terrainVersion;
    private final String costProfileVersion;
    private final int originX, originZ, spacing;
    private final EdgeCostCalculator calculator;
    private final ConcurrentHashMap<PairKey, EdgeCost> cache = new ConcurrentHashMap<>();
    private final LongAdder computations = new LongAdder();
    private final LongAdder hits = new LongAdder();

    public AdjacentEdgeCache(String terrainVersion, String costProfileVersion, int originX, int originZ,
                             int spacing, EdgeCostCalculator calculator) {
        this.terrainVersion = Objects.requireNonNull(terrainVersion);
        this.costProfileVersion = Objects.requireNonNull(costProfileVersion);
        this.originX = originX; this.originZ = originZ; this.spacing = spacing; this.calculator = calculator;
    }

    public Directed edge(Node from, Node to) {
        int dx = StrictMath.abs(from.gridX - to.gridX), dz = StrictMath.abs(from.gridZ - to.gridZ);
        if (dx > 1 || dz > 1 || dx + dz == 0) throw new IllegalArgumentException("nodes must be distinct neighbors");
        boolean canonical = from.compareTo(to) < 0;
        Node low = canonical ? from : to, high = canonical ? to : from;
        PairKey key = new PairKey(terrainVersion, costProfileVersion, originX, originZ, spacing, low, high);
        EdgeCost existing = cache.get(key);
        if (existing != null) hits.increment();
        EdgeCost both = existing != null ? existing : cache.computeIfAbsent(key, ignored -> {
            computations.increment();
            return calculator.calculate(worldX(low), worldZ(low), worldX(high), worldZ(high));
        });
        if (!both.passable()) return Directed.BLOCKED;
        return new Directed(true, canonical ? both.forwardMicros() : both.reverseMicros());
    }

    public int worldX(Node node) { return Math.addExact(originX, Math.multiplyExact(node.gridX, spacing)); }
    public int worldZ(Node node) { return Math.addExact(originZ, Math.multiplyExact(node.gridZ, spacing)); }
    public Stats stats() { return new Stats(computations.sum(), hits.sum(), cache.size()); }

    public record Node(int gridX, int gridZ) implements Comparable<Node> {
        @Override public int compareTo(Node other) {
            int byX = Integer.compare(gridX, other.gridX);
            return byX != 0 ? byX : Integer.compare(gridZ, other.gridZ);
        }
    }
    public record Directed(boolean passable, long micros) {
        public static final Directed BLOCKED = new Directed(false, Long.MAX_VALUE);
    }
    public record Stats(long computations, long hits, int entries) {}
    private record PairKey(String terrainVersion, String costVersion, int originX, int originZ, int spacing,
                           Node low, Node high) {}
}
