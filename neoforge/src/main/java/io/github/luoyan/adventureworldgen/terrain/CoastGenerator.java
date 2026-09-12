package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.noise.DeterministicRandom;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.*;
import io.github.luoyan.adventureworldgen.noise.GradientNoise;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/** FTF UpliftContinentGenerator adaptation: warped Voronoi edge field, frozen as a contour.
 * A single connected continent is selected; the radius scales its extent, never clips it to a circle. */
public final class CoastGenerator {
    private final PlannerProfile profile;
    public CoastGenerator(PlannerProfile profile) { this.profile = profile; }

    public Result generate(long seed, double radius, double keepRadius) {
        if (!(radius > 0) || !Double.isFinite(radius) || keepRadius < 0 || !Double.isFinite(keepRadius))
            throw new IllegalArgumentException("invalid continent dimensions");
        if (keepRadius >= radius * 0.5) throw new PlanningFailure(PlanningFailure.Code.CONFIG_CONFLICT, FailureStage.COAST, "spawn reservation exceeds the continent interior", Map.of("keep_radius", keepRadius));
        Field field = new Field(seed);
        List<Vec2> vertices = contour(field, profile.coast().initialResolution());
        // Uniform scaling preserves bays, peninsulas and the non-circular outline.
        double extent = vertices.stream().mapToDouble(p -> StrictMath.hypot(p.x(), p.z())).max().orElseThrow();
        double scale = radius / extent;
        var detail = new FractalCoastWarp(seed, radius);
        List<Vec2> points = detailedContour(vertices, scale, detail);
        Coastline coarse = new Coastline(points);
        double error = Double.POSITIVE_INFINITY;
        int resolution = profile.coast().initialResolution() * 2;
        while (true) {
            var refined = detailedContour(contour(field, resolution), scale, detail);
            Coastline fine = new Coastline(refined);
            error = 0;
            for (Vec2 p : refined) error = StrictMath.max(error, coarse.nearestPoint(p.x(), p.z()).distance());
            for (Vec2 p : coarse.vertices()) error = StrictMath.max(error, fine.nearestPoint(p.x(), p.z()).distance());
            if (error <= profile.coast().maximumPolylineError(radius)) { coarse = fine; break; }
            if (resolution >= profile.coast().maximumResolution()) throw new PlanningFailure(PlanningFailure.Code.PRECISION_INSUFFICIENT, FailureStage.COAST, "warped continent contour failed refinement tolerance", Map.of("error", error));
            coarse = fine; resolution *= 2;
        }
        // Remove the small extent difference introduced by contour refinement.
        double finalScale = radius / coarse.vertices().stream().mapToDouble(p -> StrictMath.hypot(p.x(), p.z())).max().orElseThrow();
        double tolerance = StrictMath.min(0.25, StrictMath.max(0,
                (profile.coast().maximumPolylineError(radius) - error * finalScale) * 0.5));
        Coastline coast = new Coastline(simplify(coarse.vertices().stream()
                .map(p -> new Vec2(p.x() * finalScale, p.z() * finalScale)).toList(), tolerance));
        if (coast.vertices().size() > profile.coast().maximumVertices()) throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, FailureStage.COAST, "contour exceeds vertex budget", Map.of("vertices", coast.vertices().size()));
        if (coast.signedDistance(0, 0) < keepRadius) throw new PlanningFailure(PlanningFailure.Code.CONFIG_CONFLICT, FailureStage.COAST, "warped continent does not contain the spawn reservation", Map.of("keep_radius", keepRadius));
        return new Result(coast, coast.vertices().size(), error * finalScale + tolerance,
                profile.coast().landBand(radius), profile.coast().seaBand(radius));
    }

    private static List<Vec2> detailedContour(List<Vec2> source, double scale, FractalCoastWarp detail) {
        List<Vec2> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            Vec2 a = source.get(i), b = source.get((i + 1) % source.size());
            int divisions = StrictMath.max(1, (int) StrictMath.ceil(a.distance(b) * scale / 0.5));
            for (int j = 0; j < divisions; j++) {
                Vec2 p = a.interpolate(b, j / (double) divisions);
                Vec2 displaced = detail.apply(p.x() * scale, p.z() * scale);
                result.add(displaced);
            }
        }
        return List.copyOf(result);
    }

    private static List<Vec2> simplify(List<Vec2> input, double tolerance) {
        List<Vec2> closed = new ArrayList<>(input); closed.add(input.getFirst());
        boolean[] keep = new boolean[closed.size()]; keep[0] = true; keep[keep.length - 1] = true;
        ArrayDeque<int[]> stack = new ArrayDeque<>(); stack.push(new int[]{0, closed.size() - 1});
        while (!stack.isEmpty()) {
            int[] interval = stack.pop(); int a = interval[0], b = interval[1], farthest = -1;
            double maximum = tolerance * tolerance;
            Vec2 start = closed.get(a), end = closed.get(b);
            double dx = end.x() - start.x(), dz = end.z() - start.z(), length = dx * dx + dz * dz;
            for (int i = a + 1; i < b; i++) {
                Vec2 p = closed.get(i);
                double t = length == 0 ? 0 : StrictMath.max(0, StrictMath.min(1,
                        ((p.x() - start.x()) * dx + (p.z() - start.z()) * dz) / length));
                double ex = p.x() - start.x() - t * dx, ez = p.z() - start.z() - t * dz;
                double distance = ex * ex + ez * ez;
                if (distance > maximum) { maximum = distance; farthest = i; }
            }
            if (farthest >= 0) { keep[farthest] = true; stack.push(new int[]{a, farthest}); stack.push(new int[]{farthest, b}); }
        }
        List<Vec2> result = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) if (keep[i]) result.add(input.get(i));
        return List.copyOf(result);
    }

    /** Same two-axis macro warp for the coastline and its cell field, as in FTF's uplift generator.
     * High octaves decay rapidly: they round local bays instead of creating angular radial teeth. */
    private static final class Field {
        final GradientNoise[] wx = new GradientNoise[4], wz = new GradientNoise[4];
        final Vec2[] neighbors = new Vec2[8];
        Field(long seed) {
            for (int i = 0; i < 4; i++) {
                wx[i] = new GradientNoise(seed, "ftf/coast/x/" + i, 0.9 / StrictMath.pow(2.2, i));
                wz[i] = new GradientNoise(seed, "ftf/coast/z/" + i, 0.9 / StrictMath.pow(2.2, i));
            }
            int i = 0;
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) if (x != 0 || z != 0) {
                String id = x + "/" + z;
                double jx = DeterministicRandom.sample(seed, "coast-r6", "cell", id, 0) - 0.5;
                double jz = DeterministicRandom.sample(seed, "coast-r6", "cell", id, 1) - 0.5;
                neighbors[i++] = new Vec2(x * 1.9 + jx * 0.95, z * 1.9 + jz * 0.95);
            }
        }
        double sample(double x, double z) {
            double dx = 0, dz = 0, amplitude = 0.62;
            for (int i = 0; i < 4; i++) {
                dx += amplitude * wx[i].sample(x, z); dz += amplitude * wz[i].sample(x, z);
                amplitude *= 0.42;
            }
            x += dx; z += dz;
            double edge = Double.POSITIVE_INFINITY;
            for (Vec2 p : neighbors) {
                double length = StrictMath.hypot(p.x(), p.z());
                double distance = (length * length * 0.5 - x * p.x() - z * p.z()) / length;
                double h = StrictMath.max(0, 0.08 - StrictMath.abs(edge - distance)) / 0.08;
                edge = StrictMath.min(edge, distance) - h * h * 0.02;
            }
            return edge - 0.10;
        }
    }

    /** Marching triangles removes ambiguous saddle cells and produces degree-two closed contours. */
    private static List<Vec2> contour(Field field, int resolution) {
        int side = resolution + 1;
        double step = 4.0 / resolution;
        double[] values = new double[side * side];
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++)
            values[z * side + x] = field.sample(-2 + x * step, -2 + z * step);
        Map<Long, Integer> edges = new HashMap<>();
        List<Vec2> points = new ArrayList<>(); List<List<Integer>> links = new ArrayList<>();
        for (int z = 0; z < resolution; z++) for (int x = 0; x < resolution; x++) {
            int a = z * side + x, b = a + 1, c = a + side, d = c + 1;
            triangle(a, b, d, values, side, step, edges, points, links);
            triangle(a, d, c, values, side, step, edges, points, links);
        }
        boolean[] visited = new boolean[points.size()]; List<Vec2> largest = List.of();
        for (int start = 0; start < points.size(); start++) if (!visited[start]) {
            List<Vec2> loop = new ArrayList<>(); int previous = -1, current = start;
            do {
                if (links.get(current).size() != 2) throw new IllegalStateException("open continent contour");
                visited[current] = true; loop.add(points.get(current));
                int next = links.get(current).get(0) == previous ? links.get(current).get(1) : links.get(current).get(0);
                previous = current; current = next;
            } while (current != start && !visited[current]);
            if (loop.size() >= 3 && new Coastline(loop).contains(0, 0) && loop.size() > largest.size()) largest = loop;
        }
        if (largest.isEmpty()) throw new IllegalStateException("continent lost its central component");
        return List.copyOf(largest);
    }
    private static void triangle(int a, int b, int c, double[] values, int side, double step,
                                 Map<Long, Integer> edges, List<Vec2> points, List<List<Integer>> links) {
        int[] v = {a, b, c}; int first = -1, second = -1;
        for (int i = 0; i < 3; i++) {
            int p = v[i], q = v[(i + 1) % 3];
            if ((values[p] >= 0) == (values[q] >= 0)) continue;
            int lo = Math.min(p, q), hi = Math.max(p, q);
            long key = ((long) lo << 32) | hi;
            Integer index = edges.get(key);
            if (index == null) {
                double t = values[lo] / (values[lo] - values[hi]);
                index = points.size();
                points.add(new Vec2(-2 + (lo % side + t * (hi % side - lo % side)) * step,
                        -2 + (lo / side + t * (hi / side - lo / side)) * step));
                links.add(new ArrayList<>(2)); edges.put(key, index);
            }
            if (first < 0) first = index; else second = index;
        }
        if (second >= 0) { links.get(first).add(second); links.get(second).add(first); }
    }
    /**
     * The frozen coastline and the facts a caller needs to trust it. There is deliberately no
     * cached equal-arc sample list here: {@code CostPlanner} samples the polyline on demand with
     * its injected profile, and a second copy computed at generation time would be a second data
     * path between generating a coast and restoring one. {@link Coastline#equalArcSamples} stays
     * the single implementation.
     */
    public record Result(Coastline coastline, int vertexCount, double estimatedMaximumError,
                         double landBand, double seaBand) {}
}
