package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.spatial.Vec2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** The immutable final polyline used for every land/sea and distance query. */
public final class Coastline {
    private static final double BOUNDARY_EPSILON_SQUARED = 1.0e-18;

    private final List<Vec2> vertices;
    private final Segment[] segments;
    private final Node index;
    private final double perimeter;

    public Coastline(List<Vec2> vertices) {
        if (vertices.size() < 3) throw new IllegalArgumentException("a coastline needs at least three vertices");
        this.vertices = List.copyOf(vertices);
        this.segments = new Segment[vertices.size()];
        double length = 0.0;
        for (int i = 0; i < vertices.size(); i++) {
            Segment segment = new Segment(i, vertices.get(i), vertices.get((i + 1) % vertices.size()), length);
            if (!(segment.length > 0.0)) throw new IllegalArgumentException("adjacent coastline vertices must differ");
            segments[i] = segment;
            length += segment.length;
        }
        perimeter = length;
        Integer[] order = new Integer[segments.length];
        Arrays.setAll(order, i -> i);
        index = build(order, 0, order.length);
    }

    public List<Vec2> vertices() { return vertices; }
    public double perimeter() { return perimeter; }

    /** Includes the polyline boundary in the land mask. */
    public boolean contains(double x, double z) {
        int crossings = crossings(index, x, z);
        return crossings < 0 || (crossings & 1) != 0;
    }

    private int crossings(Node node, double x, double z) {
        if (z < node.minZ || z > node.maxZ || x > node.maxX) return 0;
        if (node.segment >= 0) {
            Segment s = segments[node.segment];
            if (s.nearest(x, z).distanceSquared <= BOUNDARY_EPSILON_SQUARED) return -1;
            if ((s.a.z() > z) == (s.b.z() > z)) return 0;
            double intersection = s.a.x() + (z - s.a.z()) * (s.b.x() - s.a.x()) / (s.b.z() - s.a.z());
            return intersection > x ? 1 : 0;
        }
        int left = crossings(node.left, x, z), right = crossings(node.right, x, z);
        return left < 0 || right < 0 ? -1 : left + right;
    }

    public double signedDistance(double x, double z) {
        Nearest nearest = nearest(x, z);
        if (nearest.distanceSquared <= BOUNDARY_EPSILON_SQUARED) return 0.0;
        double distance = StrictMath.sqrt(nearest.distanceSquared);
        return contains(x, z) ? distance : -distance;
    }

    public NearestPoint nearestPoint(double x, double z) {
        Nearest nearest = nearest(x, z);
        return new NearestPoint(nearest.segment.index, nearest.point,
                StrictMath.sqrt(nearest.distanceSquared));
    }

    /** Equal-arc samples, with no duplicate closing endpoint. */
    public List<Vec2> equalArcSamples(double targetSpacing, int maximumSamples) {
        if (!(targetSpacing > 0.0) || maximumSamples < 3) throw new IllegalArgumentException("invalid sample limits");
        int count = StrictMath.max(3, (int) StrictMath.ceil(perimeter / targetSpacing));
        if (count > maximumSamples) throw new IllegalArgumentException("coastline sample limit exceeded: " + count);
        List<Vec2> result = new ArrayList<>(count);
        int segmentIndex = 0;
        for (int i = 0; i < count; i++) {
            double target = i * perimeter / count;
            while (segmentIndex + 1 < segments.length
                    && segments[segmentIndex + 1].startDistance <= target) segmentIndex++;
            Segment segment = segments[segmentIndex];
            result.add(segment.a.interpolate(segment.b, (target - segment.startDistance) / segment.length));
        }
        return List.copyOf(result);
    }

    private Nearest nearest(double x, double z) {
        PriorityQueue<NodeDistance> queue = new PriorityQueue<>(Comparator
                .comparingDouble(NodeDistance::lowerBoundSquared)
                .thenComparingInt(item -> item.node.minimumSegment));
        queue.add(new NodeDistance(index, index.distanceSquared(x, z)));
        Nearest best = null;
        while (!queue.isEmpty()) {
            NodeDistance candidate = queue.remove();
            if (best != null && candidate.lowerBoundSquared > best.distanceSquared) break;
            Node node = candidate.node;
            if (node.segment >= 0) {
                Nearest measured = segments[node.segment].nearest(x, z);
                if (best == null || measured.distanceSquared < best.distanceSquared
                        || (measured.distanceSquared == best.distanceSquared
                        && measured.segment.index < best.segment.index)) best = measured;
            } else {
                queue.add(new NodeDistance(node.left, node.left.distanceSquared(x, z)));
                queue.add(new NodeDistance(node.right, node.right.distanceSquared(x, z)));
            }
        }
        return best;
    }

    private Node build(Integer[] order, int from, int to) {
        if (to - from == 1) return Node.leaf(segments[order[from]]);
        double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = from; i < to; i++) {
            Segment s = segments[order[i]];
            minX = StrictMath.min(minX, s.minX); maxX = StrictMath.max(maxX, s.maxX);
            minZ = StrictMath.min(minZ, s.minZ); maxZ = StrictMath.max(maxZ, s.maxZ);
        }
        boolean splitX = maxX - minX >= maxZ - minZ;
        Arrays.sort(order, from, to, Comparator
                .comparingDouble((Integer i) -> splitX ? segments[i].midX : segments[i].midZ)
                .thenComparingInt(Integer::intValue));
        int middle = (from + to) >>> 1;
        return Node.branch(build(order, from, middle), build(order, middle, to));
    }

    public record NearestPoint(int segmentIndex, Vec2 point, double distance) {}

    private record Nearest(Segment segment, Vec2 point, double distanceSquared) {}
    private record NodeDistance(Node node, double lowerBoundSquared) {}

    private static final class Segment {
        final int index;
        final Vec2 a, b;
        final double length, startDistance, minX, maxX, minZ, maxZ, midX, midZ;

        Segment(int index, Vec2 a, Vec2 b, double startDistance) {
            this.index = index; this.a = a; this.b = b; this.startDistance = startDistance;
            length = a.distance(b);
            minX = StrictMath.min(a.x(), b.x()); maxX = StrictMath.max(a.x(), b.x());
            minZ = StrictMath.min(a.z(), b.z()); maxZ = StrictMath.max(a.z(), b.z());
            midX = (a.x() + b.x()) * 0.5; midZ = (a.z() + b.z()) * 0.5;
        }

        Nearest nearest(double x, double z) {
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double t = ((x - a.x()) * dx + (z - a.z()) * dz) / (dx * dx + dz * dz);
            t = StrictMath.max(0.0, StrictMath.min(1.0, t));
            Vec2 point = new Vec2(a.x() + t * dx, a.z() + t * dz);
            double px = x - point.x(), pz = z - point.z();
            return new Nearest(this, point, px * px + pz * pz);
        }
    }

    private static final class Node {
        final double minX, maxX, minZ, maxZ;
        final int minimumSegment, segment;
        final Node left, right;

        private Node(double minX, double maxX, double minZ, double maxZ, int minimumSegment,
                     int segment, Node left, Node right) {
            this.minX = minX; this.maxX = maxX; this.minZ = minZ; this.maxZ = maxZ;
            this.minimumSegment = minimumSegment; this.segment = segment; this.left = left; this.right = right;
        }

        static Node leaf(Segment segment) {
            return new Node(segment.minX, segment.maxX, segment.minZ, segment.maxZ,
                    segment.index, segment.index, null, null);
        }

        static Node branch(Node left, Node right) {
            return new Node(StrictMath.min(left.minX, right.minX), StrictMath.max(left.maxX, right.maxX),
                    StrictMath.min(left.minZ, right.minZ), StrictMath.max(left.maxZ, right.maxZ),
                    StrictMath.min(left.minimumSegment, right.minimumSegment), -1, left, right);
        }

        double distanceSquared(double x, double z) {
            double dx = x < minX ? minX - x : x > maxX ? x - maxX : 0.0;
            double dz = z < minZ ? minZ - z : z > maxZ ? z - maxZ : 0.0;
            return dx * dx + dz * dz;
        }
    }
}
