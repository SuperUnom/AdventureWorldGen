package io.github.luoyan.adventureworldgen.cost;

import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Directed;
import io.github.luoyan.adventureworldgen.cost.AdjacentEdgeCache.Node;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.spatial.Vec2;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/** Complete single-source planner-v2 Dijkstra result for one finite aligned grid. */
public final class CostDistanceMap {
    public static final long UNREACHABLE = Long.MAX_VALUE;
    private static final int[] NEIGHBOR_X = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] NEIGHBOR_Z = {-1, 0, 1, -1, 1, -1, 0, 1};

    private final Bounds bounds;
    private final int originX, originZ, spacing;
    private final Predicate<Node> allowed;
    private final EdgeCostCalculator shortConnections;
    private final long[] distances;

    private CostDistanceMap(Bounds bounds, int originX, int originZ, int spacing, Predicate<Node> allowed,
                            EdgeCostCalculator shortConnections, long[] distances) {
        this.bounds = bounds; this.originX = originX; this.originZ = originZ; this.spacing = spacing;
        this.allowed = allowed; this.shortConnections = shortConnections; this.distances = distances;
    }

    public static CostDistanceMap build(Bounds bounds, int originX, int originZ, int spacing,
                                        Predicate<Node> allowed, CostGraph graph,
                                        EdgeCostCalculator shortConnections, Vec2 source,
                                        int maximumNodes, long maximumWorkingMemoryBytes) {
        return build(bounds, originX, originZ, spacing, allowed, graph, shortConnections, source,
                maximumNodes, maximumWorkingMemoryBytes, ignored -> {});
    }

    public static CostDistanceMap build(Bounds bounds, int originX, int originZ, int spacing,
                                        Predicate<Node> allowed, CostGraph graph,
                                        EdgeCostCalculator shortConnections, Vec2 source,
                                        int maximumNodes, long maximumWorkingMemoryBytes,
                                        java.util.function.DoubleConsumer progress) {
        long nodeCount = bounds.nodeCount();
        long estimatedBytes = Math.multiplyExact(nodeCount, 25L);
        if (nodeCount > maximumNodes || estimatedBytes > maximumWorkingMemoryBytes || nodeCount > Integer.MAX_VALUE) {
            throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, FailureStage.COST_GRAPH,
                    "cost graph exceeds planner-v2 resource limits",
                    Map.of("nodes", nodeCount, "maximum_nodes", maximumNodes,
                            "estimated_bytes", estimatedBytes, "maximum_bytes", maximumWorkingMemoryBytes));
        }
        long[] distances = new long[(int) nodeCount];
        Arrays.fill(distances, UNREACHABLE);
        boolean[] settled = new boolean[(int) nodeCount];
        PrimitiveMinHeap queue = new PrimitiveMinHeap((int) nodeCount, distances, bounds);

        forEachNearby(source, originX, originZ, spacing, node -> {
            if (!bounds.contains(node) || !allowed.test(node)) return;
            double nx = originX + node.gridX() * (double) spacing;
            double nz = originZ + node.gridZ() * (double) spacing;
            long cost;
            if (source.x() == nx && source.z() == nz) cost = 0L;
            else {
                EdgeCost edge = shortConnections.calculate(source.x(), source.z(), nx, nz);
                if (!edge.passable()) return;
                cost = edge.forwardMicros();
            }
            int index = bounds.index(node);
            if (cost < distances[index]) {
                distances[index] = cost;
                queue.offerOrDecrease(index);
            }
        });

        int completed = 0;
        while (!queue.isEmpty()) {
            int currentIndex = queue.removeMin();
            Node current = bounds.node(currentIndex);
            if (settled[currentIndex]) continue;
            settled[currentIndex] = true;
            if ((++completed & 255) == 0) progress.accept(completed / (double) nodeCount);
            for (int i = 0; i < NEIGHBOR_X.length; i++) {
                Node next = new Node(current.gridX() + NEIGHBOR_X[i], current.gridZ() + NEIGHBOR_Z[i]);
                if (!bounds.contains(next) || !allowed.test(next)) continue;
                int nextIndex = bounds.index(next);
                if (settled[nextIndex]) continue;
                Directed edge = graph.edge(current, next);
                if (!edge.passable()) continue;
                long candidate;
                try {
                    candidate = Math.addExact(distances[currentIndex], edge.micros());
                } catch (ArithmeticException overflow) {
                    throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, FailureStage.COST_GRAPH,
                            "Dijkstra distance overflow", Map.of("node", current, "edge_cost", edge.micros()));
                }
                if (candidate < distances[nextIndex]) {
                    distances[nextIndex] = candidate;
                    queue.offerOrDecrease(nextIndex);
                }
            }
        }
        progress.accept(1);
        return new CostDistanceMap(bounds, originX, originZ, spacing, allowed, shortConnections, distances);
    }

    /** Real directed node-to-point connections to the containing grid face plus one outer ring. */
    public long costAt(Vec2 point) {
        final long[] best = {UNREACHABLE};
        forEachNearby(point, originX, originZ, spacing, node -> {
            if (!bounds.contains(node) || !allowed.test(node)) return;
            long base = distances[bounds.index(node)];
            if (base == UNREACHABLE) return;
            double nx = originX + node.gridX() * (double) spacing;
            double nz = originZ + node.gridZ() * (double) spacing;
            long edgeCost;
            if (point.x() == nx && point.z() == nz) edgeCost = 0L;
            else {
                EdgeCost edge = shortConnections.calculate(nx, nz, point.x(), point.z());
                if (!edge.passable()) return;
                edgeCost = edge.forwardMicros();
            }
            if (base <= Long.MAX_VALUE - edgeCost) best[0] = StrictMath.min(best[0], base + edgeCost);
        });
        return best[0];
    }

    public long nodeCost(Node node) {
        return bounds.contains(node) ? distances[bounds.index(node)] : UNREACHABLE;
    }

    private static void forEachNearby(Vec2 point, int originX, int originZ, int spacing,
                                      java.util.function.Consumer<Node> consumer) {
        int gx = floorToInt((point.x() - originX) / spacing);
        int gz = floorToInt((point.z() - originZ) / spacing);
        for (int x = gx - 1; x <= gx + 2; x++) for (int z = gz - 1; z <= gz + 2; z++)
            consumer.accept(new Node(x, z));
    }

    private static int floorToInt(double value) {
        double floor = StrictMath.floor(value);
        if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) throw new ArithmeticException("grid coordinate overflow");
        return (int) floor;
    }

    public record Bounds(int minGridX, int maxGridX, int minGridZ, int maxGridZ) {
        public Bounds {
            if (minGridX > maxGridX || minGridZ > maxGridZ) throw new IllegalArgumentException("empty grid bounds");
        }
        public boolean contains(Node node) {
            return node.gridX() >= minGridX && node.gridX() <= maxGridX
                    && node.gridZ() >= minGridZ && node.gridZ() <= maxGridZ;
        }
        public long nodeCount() {
            return Math.multiplyExact((long) maxGridX - minGridX + 1, (long) maxGridZ - minGridZ + 1);
        }
        int index(Node node) {
            long width = (long) maxGridZ - minGridZ + 1;
            return Math.toIntExact(((long) node.gridX() - minGridX) * width + node.gridZ() - minGridZ);
        }
        Node node(int index) {
            int width = maxGridZ - minGridZ + 1;
            return new Node(minGridX + index / width, minGridZ + index % width);
        }
    }

    /** Fixed-capacity decrease-key heap: every grid node appears at most once. */
    private static final class PrimitiveMinHeap {
        private final int[] heap, positions;
        private final long[] distances;
        private final Bounds bounds;
        private int size;

        PrimitiveMinHeap(int capacity, long[] distances, Bounds bounds) {
            heap = new int[capacity];
            positions = new int[capacity];
            Arrays.fill(positions, -1);
            this.distances = distances;
            this.bounds = bounds;
        }
        boolean isEmpty() { return size == 0; }
        void offerOrDecrease(int node) {
            int position = positions[node];
            if (position < 0) {
                position = size++;
                heap[position] = node;
                positions[node] = position;
            }
            siftUp(position);
        }
        int removeMin() {
            int result = heap[0];
            positions[result] = -1;
            int last = heap[--size];
            if (size > 0) {
                heap[0] = last;
                positions[last] = 0;
                siftDown(0);
            }
            return result;
        }
        private void siftUp(int position) {
            while (position > 0) {
                int parent = (position - 1) >>> 1;
                if (!less(heap[position], heap[parent])) break;
                swap(position, parent);
                position = parent;
            }
        }
        private void siftDown(int position) {
            while (true) {
                int left = position * 2 + 1;
                if (left >= size) return;
                int right = left + 1;
                int best = right < size && less(heap[right], heap[left]) ? right : left;
                if (!less(heap[best], heap[position])) return;
                swap(position, best);
                position = best;
            }
        }
        private boolean less(int a, int b) {
            int byDistance = Long.compare(distances[a], distances[b]);
            return byDistance < 0 || byDistance == 0 && compareNode(a, b) < 0;
        }
        private int compareNode(int a, int b) {
            Node left = bounds.node(a), right = bounds.node(b);
            int byX = Integer.compare(left.gridX(), right.gridX());
            return byX != 0 ? byX : Integer.compare(left.gridZ(), right.gridZ());
        }
        private void swap(int a, int b) {
            int value = heap[a]; heap[a] = heap[b]; heap[b] = value;
            positions[heap[a]] = a; positions[heap[b]] = b;
        }
    }
}
