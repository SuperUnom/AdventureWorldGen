package io.github.luoyan.adventureworldgen.hydrology;

import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.planner.DeterministicRandom;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import io.github.luoyan.adventureworldgen.planner.PlanningFailure;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Adapted deterministic FTF river graph using the project's fixed coast and stable random keys. */
public final class HydrologyGenerator {
    private final PlannerProfile planner;
    private final HydrologyProfile profile;

    public HydrologyGenerator(PlannerProfile planner, HydrologyProfile profile) {
        this.planner = planner; this.profile = profile;
    }

    public RiverNetwork generate(long seed, double radius, double seaSurface,
                                 Coastline coast, MacroTerrain baseTerrain) {
        List<RiverNetwork.Channel> channels = new ArrayList<>();
        List<RiverNetwork.Wetland> wetlands = new ArrayList<>();
        List<RiverNetwork.Channel> roots = new ArrayList<>();
        // FTF generateRoots uses random bearings and independently varied headwater distances.
        // Separate catchment anchors adapt that construction to this one finite continent.
        for (int attempt = 0; roots.size() < profile.mainRiverCount() && attempt < 512; attempt++) {
            String candidateId = "river/root-candidate/" + attempt;
            double angle = random(seed, candidateId, 0) * StrictMath.PI * 2;
            double anchorAngle = random(seed, candidateId, 1) * StrictMath.PI * 2;
            double anchorRadius = radius * 0.28 * StrictMath.sqrt(random(seed, candidateId, 2));
            Vec2 anchor = new Vec2(StrictMath.cos(anchorAngle) * anchorRadius, StrictMath.sin(anchorAngle) * anchorRadius);
            if (!coast.contains(anchor.x(), anchor.z())) continue;
            double dx = StrictMath.cos(angle), dz = StrictMath.sin(angle);
            double low = 0, high = 0;
            for (double distance = 16; distance <= radius * 3; distance += 16) {
                if (!coast.contains(anchor.x() + dx * distance, anchor.z() + dz * distance)) { high = distance; break; }
                low = distance;
            }
            if (high == 0) continue;
            for (int refine = 0; refine < 40; refine++) {
                double mid = (low + high) * 0.5;
                if (coast.contains(anchor.x() + dx * mid, anchor.z() + dz * mid)) low = mid; else high = mid;
            }
            Vec2 outlet = new Vec2(anchor.x() + dx * low, anchor.z() + dz * low);
            double startDistance = StrictMath.max(StrictMath.min(400, radius * 0.22),
                    (0.05 + 0.45 * random(seed, candidateId, 3)) * low);
            if (low - startDistance < radius * 0.22) continue;
            Vec2 source = new Vec2(anchor.x() + dx * startDistance, anchor.z() + dz * startDistance);
            if (StrictMath.hypot(source.x(), source.z()) < StrictMath.min(320, radius * 0.18)) continue;
            if (roots.stream().anyMatch(root -> root.points().getLast().distance(outlet) < radius * 0.18)) continue;
            String id = "river/main/" + roots.size();
            List<Vec2> points = warpedLine(seed, candidateId, source, outlet, coast, 8.0, 125, 175);
            if (points.isEmpty()) continue;
            double sourceWater = StrictMath.max(seaSurface + 2.0,
                    baseTerrain.sample(source.x(), source.z()).groundSurface() - profile.main().minimumBankHeight());
            RiverNetwork.Channel main = channel(seed, id, 0, null, points, sourceWater, seaSurface, profile.main(), baseTerrain);
            if (intersectsExisting(main, channels, null)) continue;
            channels.add(main); roots.add(main); maybeWetland(seed, main, wetlands, baseTerrain);
        }
        if (roots.size() < profile.mainRiverCount()) throw rejected("roots", "bounded irregular river candidates exhausted");
        for (RiverNetwork.Channel root : roots)
            generateForks(seed, radius, coast, baseTerrain, root, 1, channels, wetlands);
        validateMouths(channels, coast, seaSurface);
        return new RiverNetwork(channels, wetlands, planner.hydrologyVersion());
    }

    private void generateForks(long seed, double radius, Coastline coast, MacroTerrain terrain,
                               RiverNetwork.Channel parent, int depth,
                               List<RiverNetwork.Channel> channels, List<RiverNetwork.Wetland> wetlands) {
        if (depth > profile.maximumForkDepth() || parent.length() * 0.44 < 300) return;
        int forkIndex = 0;
        for (double along = 0.25 + 0.08 * random(seed, parent.id() + "/first-fork", depth); along < 0.9;
             along += 0.16 + 0.18 * random(seed, parent.id() + "/fork-spacing", forkIndex++)) {
            Vec2 join = pointAt(parent, along);
            Vec2 downstream = pointAt(parent, StrictMath.min(1.0, along + 0.01));
            double parentAngle = StrictMath.atan2(downstream.z() - join.z(), downstream.x() - join.x());
            String id = parent.id() + "/fork/" + forkIndex;
            double sign = ((forkIndex + depth) & 1) == 0 ? 1 : -1;
            double angle = parentAngle + StrictMath.PI + sign * (0.47 + 0.25 * random(seed, id, 0));
            double length = parent.length() * 0.44;
            Vec2 source = new Vec2(join.x() + StrictMath.cos(angle) * length,
                    join.z() + StrictMath.sin(angle) * length);
            if (!coast.contains(source.x(), source.z())) continue;
            List<Vec2> points = warpedLine(seed, id, source, join, coast, 8.0,
                    125 * StrictMath.pow(0.65, depth), 175 * StrictMath.pow(0.65, depth));
            if (points.isEmpty()) continue;
            double downstreamWater = waterAt(parent, along);
            double sourceWater = StrictMath.max(downstreamWater,
                    terrain.sample(source.x(), source.z()).groundSurface() - profile.branch().minimumBankHeight());
            RiverNetwork.Channel fork = channel(seed, id, depth, parent.id(), points,
                    sourceWater, downstreamWater, profile.branch(), terrain);
            if (fork == null || intersectsExisting(fork, channels, join)) continue;
            channels.add(fork);
            maybeWetland(seed, fork, wetlands, terrain);
            generateForks(seed, radius, coast, terrain, fork, depth + 1, channels, wetlands);
        }
    }

    private RiverNetwork.Channel channel(long seed, String id, int order, String parentId, List<Vec2> points,
                                          double sourceWater, double outletWater,
                                          HydrologyProfile.RiverShape shape, MacroTerrain terrain) {
        List<Double> lengths = cumulative(points);
        double total = lengths.getLast();
        List<Double> water = new ArrayList<>(points.size());
        // Follow the lowest surrounding terrain, then impose a monotone, slope-limited profile.
        // A source-to-mouth straight height interpolation can suspend water above intervening valleys.
        for (int i = 0; i < points.size(); i++) {
            Vec2 point = points.get(i);
            double cap = sourceWater;
            Vec2 previous = points.get(StrictMath.max(0, i - 1));
            Vec2 next = points.get(StrictMath.min(points.size() - 1, i + 1));
            double dx = next.x() - previous.x(), dz = next.z() - previous.z();
            double length = StrictMath.hypot(dx, dz);
            for (int step = 0; step <= 8; step++) {
                Vec2 probe = previous.interpolate(point, step / 8.0);
                double bankExtent = shape.bedWidth() + shape.bankWidth();
                for (double side = -bankExtent; side <= bankExtent; side += 4) {
                    double ground = terrain.sample(probe.x() - dz / length * side,
                            probe.z() + dx / length * side).groundSurface();
                    cap = StrictMath.min(cap, ground - shape.minimumBankHeight());
                }
            }
            if (parentId != null && cap < outletWater) return null;
            water.add(StrictMath.max(outletWater, cap));
        }
        water.set(water.size() - 1, outletWater);
        for (int i = water.size() - 2; i >= 0; i--)
            water.set(i, StrictMath.min(water.get(i), water.get(i + 1)
                    + 0.06 * (lengths.get(i + 1) - lengths.get(i))));
        for (int i = 1; i < water.size(); i++) water.set(i, StrictMath.min(water.get(i), water.get(i - 1)));
        RiverNetwork.LakeWidening lake = null;
        if (order == 0 && random(seed, id + "/lake", 0) < profile.lake().chance()) {
            double along = 0.18 + random(seed, id + "/lake", 1) * 0.42;
            double size = profile.lake().minimumSize() + random(seed, id + "/lake", 2)
                    * (profile.lake().maximumSize() - profile.lake().minimumSize());
            int centerIndex = 0;
            while (centerIndex + 1 < lengths.size() && lengths.get(centerIndex + 1) < along * total) centerIndex++;
            Vec2 center = points.get(centerIndex).interpolate(points.get(centerIndex + 1),
                    (along * total - lengths.get(centerIndex)) / (lengths.get(centerIndex + 1) - lengths.get(centerIndex)));
            double extent = size * 2.5 + shape.bedWidth() + 16;
            double lowWater = Double.POSITIVE_INFINITY, highWater = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < lengths.size(); i++) if (StrictMath.abs(lengths.get(i) - along * total) <= extent) {
                lowWater = StrictMath.min(lowWater, water.get(i)); highWater = StrictMath.max(highWater, water.get(i));
            }
            // A lake is a small basin on a level reach, not a circular excavation across a mountainside.
            if (highWater - lowWater <= 0.15 && suitableBasin(terrain, center, center, size * 2.5, lowWater, 12)) {
                lake = new RiverNetwork.LakeWidening(along, size, profile.lake().depth());
                for (int i = 0; i < lengths.size(); i++) if (StrictMath.abs(lengths.get(i) - along * total) <= extent)
                    water.set(i, lowWater);
                for (int i = 1; i < water.size(); i++) water.set(i, StrictMath.min(water.get(i), water.get(i - 1)));
                for (int i = water.size() - 2; i >= 0; i--) water.set(i, StrictMath.min(water.get(i),
                        water.get(i + 1) + 0.06 * (lengths.get(i + 1) - lengths.get(i))));
            }
        }
        return new RiverNetwork.Channel(id, order, parentId, points, lengths, water, shape, lake);
    }

    private void maybeWetland(long seed, RiverNetwork.Channel channel, List<RiverNetwork.Wetland> wetlands,
                               MacroTerrain terrain) {
        if (channel.order() != 0 || !wetlands.isEmpty() || channel.lake() != null
                || random(seed, channel.id() + "/wetland", 0) >= profile.wetland().chance()) return;
        double start = 0.2 + random(seed, channel.id() + "/wetland", 1) * 0.55;
        double radius = profile.wetland().minimumSize() + random(seed, channel.id() + "/wetland", 3)
                * (profile.wetland().maximumSize() - profile.wetland().minimumSize());
        double end = StrictMath.min(1, start + radius / channel.length());
        double water = waterAt(channel, end);
        double margin = (radius + channel.shape().bedWidth() + 24) / channel.length();
        if (StrictMath.abs(waterAt(channel, start - margin) - water) > 0.15
                || StrictMath.abs(waterAt(channel, end + margin) - water) > 0.15) return;
        Vec2 a = pointAt(channel, start), b = pointAt(channel, end);
        if (!suitableBasin(terrain, a, b, radius + 12, water, 5)) return;
        wetlands.add(new RiverNetwork.Wetland("wetland/" + channel.id(), a, b, radius, water));
    }

    private static boolean suitableBasin(MacroTerrain terrain, Vec2 a, Vec2 b, double radius,
                                          double water, double maximumExcavation) {
        for (double x = StrictMath.min(a.x(), b.x()) - radius; x <= StrictMath.max(a.x(), b.x()) + radius; x += 8)
            for (double z = StrictMath.min(a.z(), b.z()) - radius; z <= StrictMath.max(a.z(), b.z()) + radius; z += 8) {
                var sample = terrain.sample(x, z);
                double bank = sample.groundSurface() - water;
                if (sample.wet() || bank < 2 || bank > maximumExcavation) return false;
            }
        return true;
    }

    private List<Vec2> warpedLine(long seed, String id, Vec2 start, Vec2 end, Coastline coast,
                                  double spacing, double minimumScale, double maximumScale) {
        double length = start.distance(end);
        int divisions = StrictMath.max(2, (int) StrictMath.ceil(length / spacing));
        var broad = new io.github.luoyan.adventureworldgen.terrain.GradientNoise(seed, id + "/warp/broad",
                1000 + 1000 * random(seed, id + "/warp-frequency", 0));
        var fine = new io.github.luoyan.adventureworldgen.terrain.GradientNoise(seed, id + "/warp/fine", 95);
        double dx = (end.x() - start.x()) / length, dz = (end.z() - start.z()) / length;
        double nx = dz, nz = -dx;
        double scale = minimumScale + random(seed, id + "/warp", 0) * (maximumScale - minimumScale);
        List<Vec2> result = new ArrayList<>(divisions + 1);
        for (int i = 0; i <= divisions; i++) {
            double t = i / (double) divisions;
            double fade = smooth(StrictMath.min(1.0, t / 0.15)) * smooth(StrictMath.min(1.0, (1.0 - t) / 0.25));
            double x = start.x() + (end.x() - start.x()) * t;
            double z = start.z() + (end.z() - start.z()) * t;
            double bend = broad.sample(x, z);
            double lengthFactor = length * 4.0e-4;
            double wiggle = StrictMath.min(45, 25 * lengthFactor);
            double offset = fade * (bend * scale + fine.sample(x, z) * 12.5
                    + StrictMath.sin(bend + t * StrictMath.PI * 2 * 8 * lengthFactor) * wiggle);
            Vec2 point = (i == 0) ? start : (i == divisions) ? end : new Vec2(x + nx * offset, z + nz * offset);
            if (!coast.contains(point.x(), point.z()) && i != divisions) return List.of();
            result.add(point);
        }
        return List.copyOf(result);
    }

    private static boolean intersectsExisting(RiverNetwork.Channel candidate,
                                              List<RiverNetwork.Channel> existing, Vec2 allowedJoin) {
        for (RiverNetwork.Channel other : existing) {
            for (int a = 1; a < candidate.points().size(); a++) {
                Vec2 a0 = candidate.points().get(a - 1), a1 = candidate.points().get(a);
                for (int b = 1; b < other.points().size(); b++) {
                    Vec2 b0 = other.points().get(b - 1), b1 = other.points().get(b);
                    // Two channels can miss as polylines while their valleys still overlap.
                    // Reject incompatible water datums before carving creates an aqueduct/water wall.
                    double clearance = StrictMath.max(fadeRadius(candidate.shape()) + other.shape().bedWidth(),
                            fadeRadius(other.shape()) + candidate.shape().bedWidth()) + 8;
                    if (StrictMath.max(a0.x(), a1.x()) + clearance >= StrictMath.min(b0.x(), b1.x())
                            && StrictMath.max(b0.x(), b1.x()) + clearance >= StrictMath.min(a0.x(), a1.x())
                            && StrictMath.max(a0.z(), a1.z()) + clearance >= StrictMath.min(b0.z(), b1.z())
                            && StrictMath.max(b0.z(), b1.z()) + clearance >= StrictMath.min(a0.z(), a1.z())) {
                        double distance = StrictMath.min(StrictMath.min(distanceToLineSegment(a0, b0, b1),
                                distanceToLineSegment(a1, b0, b1)), StrictMath.min(distanceToLineSegment(b0, a0, a1),
                                distanceToLineSegment(b1, a0, a1)));
                        double difference = StrictMath.abs((candidate.waterSurfaces().get(a - 1) + candidate.waterSurfaces().get(a)
                                - other.waterSurfaces().get(b - 1) - other.waterSurfaces().get(b)) * 0.5);
                        if (distance < clearance && difference > 0.5 + distance * 0.04) return true;
                    }
                    if (segmentsIntersect(a0, a1, b0, b1)) {
                        if (allowedJoin != null && (a0.distanceSquared(allowedJoin) < 1e-12
                                || a1.distanceSquared(allowedJoin) < 1e-12)
                                && distanceToLineSegment(allowedJoin, b0, b1) < 1e-6) continue;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static double fadeRadius(HydrologyProfile.RiverShape shape) {
        return shape.bedWidth() + StrictMath.max(4, shape.minimumBankHeight() * 2) + shape.bankWidth() * 5.0;
    }

    private static double distanceToLineSegment(Vec2 p, Vec2 a, Vec2 b) {
        double dx = b.x() - a.x(), dz = b.z() - a.z();
        double t = StrictMath.max(0, StrictMath.min(1, ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / (dx * dx + dz * dz)));
        return p.distance(new Vec2(a.x() + t * dx, a.z() + t * dz));
    }

    private static boolean segmentsIntersect(Vec2 a, Vec2 b, Vec2 c, Vec2 d) {
        double abC = cross(a, b, c), abD = cross(a, b, d), cdA = cross(c, d, a), cdB = cross(c, d, b);
        return abC * abD <= 0.0 && cdA * cdB <= 0.0;
    }
    private static double cross(Vec2 a, Vec2 b, Vec2 p) {
        return (b.x() - a.x()) * (p.z() - a.z()) - (b.z() - a.z()) * (p.x() - a.x());
    }

    private static List<Double> cumulative(List<Vec2> points) {
        List<Double> result = new ArrayList<>(points.size()); result.add(0.0);
        for (int i = 1; i < points.size(); i++) result.add(result.getLast() + points.get(i - 1).distance(points.get(i)));
        return List.copyOf(result);
    }

    private void validateMouths(List<RiverNetwork.Channel> channels, Coastline coast, double seaSurface) {
        for (RiverNetwork.Channel channel : channels) {
            for (int i = 1; i < channel.waterSurfaces().size(); i++) {
                if (channel.waterSurfaces().get(i) > channel.waterSurfaces().get(i - 1) + 1e-9)
                    throw rejected(channel.id(), "water surface rises downstream");
            }
            if (channel.parentId() == null) {
                Vec2 mouth = channel.points().getLast();
                if (StrictMath.abs(coast.signedDistance(mouth.x(), mouth.z())) > 1e-6
                        || StrictMath.abs(channel.waterSurfaces().getLast() - seaSurface) > 1e-9)
                    throw rejected(channel.id(), "river mouth does not meet coast at sea level");
            }
        }
    }

    static Vec2 pointAt(RiverNetwork.Channel channel, double normalized) {
        double target = channel.length() * StrictMath.max(0.0, StrictMath.min(1.0, normalized));
        int i = 1;
        while (i < channel.cumulativeLengths().size() - 1 && channel.cumulativeLengths().get(i) < target) i++;
        double before = channel.cumulativeLengths().get(i - 1), after = channel.cumulativeLengths().get(i);
        return channel.points().get(i - 1).interpolate(channel.points().get(i), (target - before) / (after - before));
    }

    static double waterAt(RiverNetwork.Channel channel, double normalized) {
        double target = channel.length() * StrictMath.max(0.0, StrictMath.min(1.0, normalized));
        int i = 1;
        while (i < channel.cumulativeLengths().size() - 1 && channel.cumulativeLengths().get(i) < target) i++;
        double before = channel.cumulativeLengths().get(i - 1), after = channel.cumulativeLengths().get(i);
        double t = (target - before) / (after - before);
        return channel.waterSurfaces().get(i - 1) + t * (channel.waterSurfaces().get(i) - channel.waterSurfaces().get(i - 1));
    }

    private double random(long seed, String id, long operation) {
        return DeterministicRandom.sample(seed, planner.algorithmVersion(), "hydrology", id, operation);
    }
    private static double smooth(double t) { return t * t * (3.0 - 2.0 * t); }
    private static PlanningFailure rejected(String id, String reason) {
        return new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, "hydrology", reason,
                Map.of("river_id", id));
    }
}
