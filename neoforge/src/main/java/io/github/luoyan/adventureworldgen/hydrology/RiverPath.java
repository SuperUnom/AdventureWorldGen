package io.github.luoyan.adventureworldgen.hydrology;

import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.noise.DeterministicRandom;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Upstream growth with persistent angular velocity and terrain-guided steering. */
final class RiverPath {
    private RiverPath() {}

    static List<Vec2> grow(long seed, String id, Vec2 source, Vec2 outlet, Coastline coast,
                          MacroTerrain terrain, HydrologyProfile.RiverShape shape, double minimumWater) {
        double length = source.distance(outlet);
        double step = StrictMath.max(16, StrictMath.min(44, 12 + shape.bedWidth()));
        double axis = StrictMath.atan2(source.z() - outlet.z(), source.x() - outlet.x());
        double heading = axis, angularVelocity = 0;
        double persistence = 0.72 + 0.22 * random(seed, id, 0);
        double mobility = 0.12 + 0.26 * random(seed, id, 1);
        List<Vec2> anchors = new ArrayList<>();
        anchors.add(outlet);
        Vec2 current = outlet;
        for (int iteration = 0; iteration < StrictMath.ceil(length / step) * 4 + 20; iteration++) {
            double remaining = current.distance(source);
            if (remaining < step * 1.6) { anchors.add(source); break; }
            // Angular momentum lets a bend develop over several steps. Random
            // acceleration changes its duration and strength without prescribing waves.
            angularVelocity = persistence * angularVelocity + (random(seed, id, iteration + 2) * 2 - 1) * mobility;
            double target = StrictMath.atan2(source.z() - current.z(), source.x() - current.x());
            double progress = ((current.x() - outlet.x()) * StrictMath.cos(axis)
                    + (current.z() - outlet.z()) * StrictMath.sin(axis)) / length;
            double pull = 0.06 + 0.75 * progress * progress * progress
                    + 0.2 * StrictMath.min(1, step * 4 / remaining);
            double preferred = angularVelocity + wrap(target - heading) * pull;
            double bestScore = Double.POSITIVE_INFINITY, bestHeading = heading;
            Vec2 best = null;
            double groundHere = terrain.sample(current.x(), current.z()).groundSurface();
            for (int choice = -6; choice <= 6; choice++) {
                double turn = choice * 0.075;
                double bearing = heading + turn;
                // Advance across the catchment without loops or doubling back into an
                // already carved reach. There is no prescribed lateral displacement.
                if (StrictMath.cos(bearing - axis) < 0.18) continue;
                Vec2 next = new Vec2(current.x() + StrictMath.cos(bearing) * step,
                        current.z() + StrictMath.sin(bearing) * step);
                if (!coast.contains(next.x(), next.z()) || next.distance(source) > remaining + step * 0.55) continue;
                double directionScore = 3 * (turn - preferred) * (turn - preferred)
                        + 0.07 * (1 - StrictMath.cos(target - bearing));
                if(directionScore>=bestScore)continue;
                double ground = terrain.sample(next.x(), next.z()).groundSurface();
                double terrainScore = directionScore + 0.012 * StrictMath.abs(ground - groundHere)
                        + 0.006 * StrictMath.max(0, ground - groundHere);
                // Flood cost is nonnegative. A candidate already worse cannot win.
                if(terrainScore>=bestScore)continue;
                double lowBank = ground;
                double bankExtent = shape.bedWidth() + shape.bankWidth();
                for (int side : new int[]{-1, 1}) lowBank = StrictMath.min(lowBank, terrain.sample(
                        next.x() - StrictMath.sin(bearing) * bankExtent * side,
                        next.z() + StrictMath.cos(bearing) * bankExtent * side).groundSurface());
                double floodPenalty = StrictMath.max(0, minimumWater + shape.minimumBankHeight() - lowBank);
                double score = terrainScore + floodPenalty * 2;
                if (score < bestScore) { best = next; bestScore = score; bestHeading = bearing; }
            }
            if (best == null) return List.of();
            current = best; heading = bestHeading; anchors.add(current);
        }
        if (!anchors.getLast().equals(source)) return List.of();
        Collections.reverse(anchors);
        // Corner cutting produces a continuous bend from the grown directions, while
        // keeping the source and confluence/outlet anchored exactly.
        for (int pass = 0; pass < 3; pass++) {
            List<Vec2> rounded = new ArrayList<>(); rounded.add(anchors.getFirst());
            for (int i = 1; i < anchors.size(); i++) {
                rounded.add(anchors.get(i - 1).interpolate(anchors.get(i), 0.25));
                rounded.add(anchors.get(i - 1).interpolate(anchors.get(i), 0.75));
            }
            rounded.add(anchors.getLast()); anchors = rounded;
        }
        List<Vec2> points = new ArrayList<>(); points.add(source);
        double carried = 0;
        for (int i = 1; i < anchors.size(); i++) {
            Vec2 a = anchors.get(i - 1), b = anchors.get(i);
            double remaining = a.distance(b);
            while (remaining + carried >= 8) {
                Vec2 point = a.interpolate(b, (8 - carried) / remaining);
                if (!coast.contains(point.x(), point.z())) return List.of();
                points.add(point); a = point; remaining = a.distance(b); carried = 0;
            }
            carried += remaining;
        }
        if (points.getLast().distanceSquared(outlet) > 1e-10) points.add(outlet);
        else points.set(points.size() - 1, outlet);
        return List.copyOf(points);
    }

    private static double random(long seed, String id, int index) {
        return DeterministicRandom.sample(seed, RiverMorphology.VERSION, "path-growth", id, index);
    }
    private static double wrap(double angle) {
        while (angle > StrictMath.PI) angle -= StrictMath.PI * 2;
        while (angle < -StrictMath.PI) angle += StrictMath.PI * 2;
        return angle;
    }
}
