package io.github.luoyan.adventureworldgen.cost;

import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Directed;
import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Node;

/**
 * Primitive-array adjacent-pair cache for a finite rectangular grid. Each undirected pair owns
 * exactly one slot containing both directed costs. This preserves compute-once semantics without
 * millions of hash keys, boxed values, and diagonal cache entries.
 */
public final class CompactGridCostGraph implements CostGraph {
    private static final byte UNKNOWN = 0, READY = 1, BLOCKED = 2;
    private final CostDistanceMap.Bounds bounds;
    private final int originX, originZ, spacing;
    private final EdgeCostCalculator calculator;
    private final int allocatedSlots;
    private final byte[] states;
    private final long[] forward;
    private final long[] reverse;
    private long computations, hits;

    public CompactGridCostGraph(CostDistanceMap.Bounds bounds, int originX, int originZ, int spacing,
                                EdgeCostCalculator calculator) {
        this.bounds = bounds;
        this.originX = originX;
        this.originZ = originZ;
        this.spacing = spacing;
        this.calculator = calculator;
        int slots = Math.multiplyExact(Math.toIntExact(bounds.nodeCount()), 4);
        this.allocatedSlots = slots;
        this.states = new byte[slots];
        this.forward = new long[slots];
        this.reverse = new long[slots];
    }

    @Override public Directed edge(Node from, Node to) {
        if (!bounds.contains(from) || !bounds.contains(to)) return Directed.BLOCKED;
        int dx = StrictMath.abs(from.gridX() - to.gridX());
        int dz = StrictMath.abs(from.gridZ() - to.gridZ());
        if (dx > 1 || dz > 1 || dx + dz == 0)
            throw new IllegalArgumentException("nodes must be distinct neighbors");
        if (dx == 1 && dz == 1) {
            Node first = new Node(to.gridX(), from.gridZ());
            Node second = new Node(from.gridX(), to.gridZ());
            if (!physical(from, first).passable() || !physical(first, to).passable()
                    || !physical(from, second).passable() || !physical(second, to).passable())
                return Directed.BLOCKED;
        }
        return physical(from, to);
    }

    private Directed physical(Node from, Node to) {
        boolean canonical = from.compareTo(to) < 0;
        Node low = canonical ? from : to;
        Node high = canonical ? to : from;
        int slot = Math.addExact(Math.multiplyExact(bounds.index(low), 4), direction(low, high));
        byte state = states[slot];
        if (state == UNKNOWN) {
            EdgeCost calculated = calculator.calculate(worldX(low), worldZ(low), worldX(high), worldZ(high));
            computations++;
            if (calculated.passable()) {
                forward[slot] = calculated.forwardMicros();
                reverse[slot] = calculated.reverseMicros();
                states[slot] = READY;
            } else states[slot] = BLOCKED;
            state = states[slot];
        } else hits++;
        if (state == BLOCKED) return Directed.BLOCKED;
        return new Directed(true, canonical ? forward[slot] : reverse[slot]);
    }

    private int worldX(Node node) { return Math.addExact(originX, Math.multiplyExact(node.gridX(), spacing)); }
    private int worldZ(Node node) { return Math.addExact(originZ, Math.multiplyExact(node.gridZ(), spacing)); }

    private static int direction(Node low, Node high) {
        int dx = high.gridX() - low.gridX(), dz = high.gridZ() - low.gridZ();
        if (dx == 0 && dz == 1) return 0;
        if (dx == 1 && dz == -1) return 1;
        if (dx == 1 && dz == 0) return 2;
        if (dx == 1 && dz == 1) return 3;
        throw new IllegalArgumentException("invalid canonical neighbor direction");
    }

    /**
     * {@code computedPairs} counts canonical undirected pairs actually computed (blocked ones
     * included), {@code allocatedSlots} reports the reserved capacity, which is
     * {@code nodeCount * 4} and must not be read as an edge count.
     */
    public AdjacentEdgeCache.Stats stats() {
        return new AdjacentEdgeCache.Stats(computations, hits, allocatedSlots);
    }

    public static long retainedBytes(long nodeCount) {
        return Math.multiplyExact(nodeCount, 4L * (Byte.BYTES + 2L * Long.BYTES));
    }
}
