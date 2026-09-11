package io.github.luoyan.adventureworldgen.hydrology;

import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.noise.DeterministicRandom;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

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
        // Spread headwaters across the interior. Independently oblique outlet bearings
        // avoid a common central hub and leave space for separate coastal catchments.
        for (int attempt = 0; roots.size() < profile.mainRiverCount(radius) && attempt < 2048; attempt++) {
            String candidateId = "river/root-candidate/" + attempt;
            double anchorAngle = random(seed, candidateId, 1) * StrictMath.PI * 2;
            double angle = anchorAngle + (random(seed, candidateId, 0) < 0.5 ? -1 : 1)
                    * (0.55 + 0.85 * random(seed, candidateId, 3));
            double anchorRadius = radius * (0.24 + 0.53 * StrictMath.sqrt(random(seed, candidateId, 2)));
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
            // If the long inland catchments fill the available terrain first, also
            // consider short coastal catchments instead of failing the entire world.
            double minimumLength = StrictMath.max(120, radius * (attempt < 1024 ? 0.48 : 0.24));
            if (low < minimumLength) continue;
            Vec2 source = anchor;
            if (StrictMath.hypot(source.x(), source.z()) < StrictMath.min(320, radius * 0.18)) continue;
            double mouthSpacing = radius * (attempt < 512 ? 0.18 : 0.10);
            if (roots.stream().anyMatch(root -> root.points().getLast().distance(outlet) < mouthSpacing)) continue;
            String id = "river/main/" + roots.size();
            var shape = variedShape(seed, id, 0, roots.isEmpty(), profile.main(), radius);
            List<Vec2> points = RiverPath.grow(seed, candidateId, source, outlet, coast, baseTerrain, shape, seaSurface);
            if (points.isEmpty() || intersectsGeometry(points,shape,channels,null)) continue;
            double sourceWater = StrictMath.max(seaSurface + 2.0,
                    baseTerrain.sample(source.x(), source.z()).groundSurface() - profile.main().minimumBankHeight());
            RiverNetwork.Channel main = channel(seed, id, 0, null, points, sourceWater, seaSurface, shape, baseTerrain, null);
            if (intersectsExisting(main, channels, null)) continue;
            channels.add(main); roots.add(main); maybeWetland(seed, main, wetlands, baseTerrain);
        }
        if (roots.size() < profile.mainRiverCount(radius)) throw rejected("roots", "bounded irregular river candidates exhausted");
        for (RiverNetwork.Channel root : roots)
            generateForks(seed, radius, coast, baseTerrain, root, 1, channels, wetlands);
        validateMouths(channels, coast, seaSurface);
        return new RiverNetwork(channels, wetlands, planner.hydrologyVersion());
    }

    private void generateForks(long seed, double radius, Coastline coast, MacroTerrain terrain,
                               RiverNetwork.Channel parent, int depth,
                               List<RiverNetwork.Channel> channels, List<RiverNetwork.Wetland> wetlands) {
        if (depth > profile.maximumForkDepth() || parent.length() < 480 || channels.size() >= 96) return;
        int forkIndex = 0;
        for (double along = 0.26 + 0.08 * random(seed, parent.id() + "/first-fork", depth); along < 0.88 && channels.size() < 96;
             along += 0.24 + 0.12 * random(seed, parent.id() + "/fork-spacing", forkIndex++)) {
            Vec2 join = pointAt(parent, along);
            Vec2 downstream = pointAt(parent, StrictMath.min(1.0, along + 0.01));
            double parentAngle = StrictMath.atan2(downstream.z() - join.z(), downstream.x() - join.x());
            String id = parent.id() + "/fork/" + forkIndex;
            // A single bearing often encounters a low valley and eliminates every
            // tributary. Try a small, stable set of local catchments before skipping it.
            for (int attempt = 0; attempt < 16; attempt++) {
                String candidate = id + "/candidate/" + attempt;
                double sign = ((forkIndex + depth + attempt) & 1) == 0 ? 1 : -1;
                double angle = parentAngle + StrictMath.PI + sign * (0.5 + 0.8 * random(seed, candidate, 0));
                double length = StrictMath.max(240, parent.length() * (0.25 + 0.30 * random(seed, candidate, 1)));
                Vec2 source = new Vec2(join.x() + StrictMath.cos(angle) * length,
                        join.z() + StrictMath.sin(angle) * length);
                if (!coast.contains(source.x(), source.z())) continue;
                var shape = variedShape(seed, id, depth, false, profile.branch(), radius);
                double downstreamWater = waterAt(parent, along);
                if (terrain.sample(source.x(), source.z()).groundSurface() < downstreamWater + shape.minimumBankHeight()) continue;
                List<Vec2> points = RiverPath.grow(seed, candidate, source, join, coast, terrain, shape, downstreamWater);
                if (points.isEmpty() || intersectsGeometry(points,shape,channels,join)) continue;
                double sourceWater = StrictMath.max(downstreamWater,
                        terrain.sample(source.x(), source.z()).groundSurface() - profile.branch().minimumBankHeight());
                RiverNetwork.Channel fork = channel(seed, id, depth, parent.id(), points,
                        sourceWater, downstreamWater, shape, terrain, parent);
                if (fork == null || intersectsExisting(fork, channels, join)) continue;
                channels.add(fork);
                maybeWetland(seed, fork, wetlands, terrain);
                generateForks(seed, radius, coast, terrain, fork, depth + 1, channels, wetlands);
                break;
            }
        }
    }

    private RiverNetwork.Channel channel(long seed, String id, int order, String parentId, List<Vec2> points,
                                          double sourceWater, double outletWater,
                                          HydrologyProfile.RiverShape shape, MacroTerrain terrain, RiverNetwork.Channel parent) {
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
            bankProbes: for (int step = 0; step <= 8; step++) {
                Vec2 probe = previous.interpolate(point, step / 8.0);
                double bankExtent = RiverMorphology.maximumBedRadius(shape) + shape.bankWidth();
                for (double side = -bankExtent; side <= bankExtent; side += 4) {
                    double ground = terrain.sample(probe.x() - dz / length * side,
                            probe.z() + dx / length * side).groundSurface();
                    cap = StrictMath.min(cap, ground - shape.minimumBankHeight());
                    if(parentId!=null&&cap<outletWater)return null;
                    // A main river's final cap is clamped to the outlet. Further
                    // lowering cannot change its water profile or lake decisions.
                    if(parentId==null&&cap<=outletWater)break bankProbes;
                }
            }
            if (parentId != null && cap < outletWater) return null;
            water.add(StrictMath.max(outletWater, cap));
        }
        if (parent != null) for (int i = 0; i < points.size() - 1; i++) {
            var nearby = nearestWater(parent, points.get(i));
            double influence = fadeRadius(shape) + RiverMorphology.maximumBedRadius(parent.shape());
            if (nearby.distance < influence) {
                double shared = RiverMorphology.maximumBedRadius(shape) + RiverMorphology.maximumBedRadius(parent.shape()) + 8;
                water.set(i, StrictMath.min(water.get(i), StrictMath.max(outletWater,
                        nearby.water + StrictMath.max(0, nearby.distance - shared) * 0.02)));
            }
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

    private HydrologyProfile.RiverShape variedShape(long seed, String id, int order, boolean broad,
                                                    HydrologyProfile.RiverShape base, double radius) {
        double size = random(seed, id + "/size", 0);
        // One broad trunk per continent, with occasional additional large catchments.
        double factor = order == 0 ? (broad || size > 0.88 ? 2.8 + size : 0.9 + size * 0.9)
                : (0.8 + size * 0.6) * StrictMath.pow(0.76, order - 1);
        double continentScale = StrictMath.max(0.4, StrictMath.min(1, radius / 3000));
        return new HydrologyProfile.RiverShape(
                StrictMath.max(2, (int) StrictMath.round(base.bedDepth() * StrictMath.sqrt(factor * continentScale))),
                base.minimumBankHeight(), base.maximumBankHeight(),
                StrictMath.max(6, (int) StrictMath.round(base.bankWidth() * continentScale)),
                StrictMath.max(3, (int) StrictMath.round(base.bedWidth() * factor * continentScale)), base.fade());
    }

    /** Reject certain polyline crossings before the expensive bank-height envelope.
     * Keep the same broad-phase bounds and confluence exception as the final check. */
    private static boolean intersectsGeometry(List<Vec2> points,HydrologyProfile.RiverShape shape,
                                              List<RiverNetwork.Channel> existing,Vec2 allowedJoin) {
        Bounds candidateBounds=bounds(points);
        for(var other:existing) {
            double clearance=StrictMath.max(fadeRadius(shape)+RiverMorphology.maximumBedRadius(other.shape()),
                    fadeRadius(other.shape())+RiverMorphology.maximumBedRadius(shape))+8;
            Bounds otherBounds=bounds(other);
            if(!candidateBounds.overlaps(otherBounds,clearance))continue;
            for(int a=1;a<points.size();a++) {
                Vec2 a0=points.get(a-1),a1=points.get(a);
                if(!new Bounds(StrictMath.min(a0.x(),a1.x()),StrictMath.min(a0.z(),a1.z()),
                        StrictMath.max(a0.x(),a1.x()),StrictMath.max(a0.z(),a1.z())).overlaps(otherBounds,clearance))continue;
                for(int b=1;b<other.points().size();b++) {
                    Vec2 b0=other.points().get(b-1),b1=other.points().get(b);
                    if(!segmentsIntersect(a0,a1,b0,b1))continue;
                    if(allowedJoin!=null&&(a0.distanceSquared(allowedJoin)<1e-12||a1.distanceSquared(allowedJoin)<1e-12)
                            &&distanceToLineSegment(allowedJoin,b0,b1)<1e-6)continue;
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean intersectsExisting(RiverNetwork.Channel candidate,
                                              List<RiverNetwork.Channel> existing, Vec2 allowedJoin) {
        Bounds candidateBounds = bounds(candidate);
        for (RiverNetwork.Channel other : existing) {
            double clearance = StrictMath.max(fadeRadius(candidate.shape()) + RiverMorphology.maximumBedRadius(other.shape()),
                    fadeRadius(other.shape()) + RiverMorphology.maximumBedRadius(candidate.shape())) + 8;
            Bounds otherBounds = bounds(other);
            if (!candidateBounds.overlaps(otherBounds, clearance)) continue;
            for (int a = 1; a < candidate.points().size(); a++) {
                Vec2 a0 = candidate.points().get(a - 1), a1 = candidate.points().get(a);
                if (!new Bounds(StrictMath.min(a0.x(), a1.x()), StrictMath.min(a0.z(), a1.z()),
                        StrictMath.max(a0.x(), a1.x()), StrictMath.max(a0.z(), a1.z())).overlaps(otherBounds, clearance)) continue;
                for (int b = 1; b < other.points().size(); b++) {
                    Vec2 b0 = other.points().get(b - 1), b1 = other.points().get(b);
                    // Two channels can miss as polylines while their valleys still overlap.
                    // Reject incompatible water datums before carving creates an aqueduct/water wall.
                    if (StrictMath.max(a0.x(), a1.x()) + clearance >= StrictMath.min(b0.x(), b1.x())
                            && StrictMath.max(b0.x(), b1.x()) + clearance >= StrictMath.min(a0.x(), a1.x())
                            && StrictMath.max(a0.z(), a1.z()) + clearance >= StrictMath.min(b0.z(), b1.z())
                            && StrictMath.max(b0.z(), b1.z()) + clearance >= StrictMath.min(a0.z(), a1.z())) {
                        double distance = StrictMath.min(StrictMath.min(distanceToLineSegment(a0, b0, b1),
                                distanceToLineSegment(a1, b0, b1)), StrictMath.min(distanceToLineSegment(b0, a0, a1),
                                distanceToLineSegment(b1, a0, a1)));
                        double difference = StrictMath.abs((candidate.waterSurfaces().get(a - 1) + candidate.waterSurfaces().get(a)
                                - other.waterSurfaces().get(b - 1) - other.waterSurfaces().get(b)) * 0.5);
                        double tolerance = 0.5 + distance * 0.04;
                        if (other.id().equals(candidate.parentId())) {
                            double wetExtent = RiverMorphology.maximumBedRadius(candidate.shape())
                                    + RiverMorphology.maximumBedRadius(other.shape()) + 4;
                            if (distance > wetExtent) tolerance = StrictMath.max(tolerance,
                                    StrictMath.min(candidate.shape().maximumBankHeight(), other.shape().maximumBankHeight()));
                        }
                        if (distance < clearance && difference > tolerance) return true;
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

    private static Bounds bounds(RiverNetwork.Channel channel) { return bounds(channel.points()); }
    private static Bounds bounds(List<Vec2> points) {
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = -minX, maxZ = -minX;
        for (Vec2 point : points) {
            minX = StrictMath.min(minX, point.x()); minZ = StrictMath.min(minZ, point.z());
            maxX = StrictMath.max(maxX, point.x()); maxZ = StrictMath.max(maxZ, point.z());
        }
        return new Bounds(minX, minZ, maxX, maxZ);
    }
    private record Bounds(double minX, double minZ, double maxX, double maxZ) {
        boolean overlaps(Bounds other, double margin) {
            return maxX + margin >= other.minX && other.maxX + margin >= minX
                    && maxZ + margin >= other.minZ && other.maxZ + margin >= minZ;
        }
    }

    private static NearbyWater nearestWater(RiverNetwork.Channel channel, Vec2 point) {
        double distance = Double.POSITIVE_INFINITY, water = 0;
        for (int i = 1; i < channel.points().size(); i++) {
            Vec2 a = channel.points().get(i - 1), b = channel.points().get(i);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double t = StrictMath.max(0, StrictMath.min(1, ((point.x() - a.x()) * dx + (point.z() - a.z()) * dz) / a.distanceSquared(b)));
            double d = point.distance(a.interpolate(b, t));
            if (d < distance) { distance = d; water = channel.waterSurfaces().get(i - 1)
                    + t * (channel.waterSurfaces().get(i) - channel.waterSurfaces().get(i - 1)); }
        }
        return new NearbyWater(distance, water);
    }
    private record NearbyWater(double distance, double water) {}

    private static double fadeRadius(HydrologyProfile.RiverShape shape) {
        return RiverMorphology.maximumBedRadius(shape) + StrictMath.max(4, shape.minimumBankHeight() * 2) + shape.bankWidth() * 5.0;
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
    private static PlanningFailure rejected(String id, String reason) {
        return new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, FailureStage.HYDROLOGY, reason,
                Map.of("river_id", id));
    }
}
