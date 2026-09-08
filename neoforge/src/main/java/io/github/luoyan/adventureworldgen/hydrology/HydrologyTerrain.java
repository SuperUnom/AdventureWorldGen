package io.github.luoyan.adventureworldgen.hydrology;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.spatial.Vec2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Four-zone river/lake/wetland carving over the immutable base terrain. */
public final class HydrologyTerrain implements MacroTerrain {
    private final MacroTerrain base;
    private final io.github.luoyan.adventureworldgen.terrain.GradientNoise lakeWarpX, lakeWarpZ, lakeFineX, lakeFineZ, wetlandMounds, wetlandWarp;
    private final RiverNetwork network;
    private final RiverMorphology morphology;
    private final Map<Long, List<ChannelSegments>> segmentBuckets;
    private final Map<Long, List<RiverNetwork.Wetland>> wetlandBuckets;
    private final Map<Long, List<LakeRef>> lakeBuckets;
    private static final int BUCKET_SIDE = 256;

    public HydrologyTerrain(MacroTerrain base, RiverNetwork network) {
        this.base = base; this.network = network;
        long seed = network.hashCode();
        morphology = new RiverMorphology(network);
        lakeWarpX = new io.github.luoyan.adventureworldgen.terrain.GradientNoise(seed, "ftf/lake/x", 200);
        lakeWarpZ = new io.github.luoyan.adventureworldgen.terrain.GradientNoise(seed, "ftf/lake/z", 200);
        lakeFineX = new io.github.luoyan.adventureworldgen.terrain.GradientNoise(seed, "ftf/lake/fine-x", 50);
        lakeFineZ = new io.github.luoyan.adventureworldgen.terrain.GradientNoise(seed, "ftf/lake/fine-z", 50);
        wetlandMounds = new io.github.luoyan.adventureworldgen.terrain.GradientNoise(seed, "ftf/wetland/mounds", 10);
        wetlandWarp = new io.github.luoyan.adventureworldgen.terrain.GradientNoise(seed, "ftf/wetland/warp", 25);
        this.segmentBuckets = indexSegments(network);
        this.wetlandBuckets = indexWetlands(network);
        this.lakeBuckets = indexLakes(network, base);
    }

    @Override
    public MacroSample sample(double x, double z) {
        MacroSample original = base.sample(x, z);
        if (original.waterKind() == WaterKind.LAVA) return original;
        Result best = null;
        long bucket = bucketKey(Math.floorDiv((int) StrictMath.floor(x), BUCKET_SIDE),
                Math.floorDiv((int) StrictMath.floor(z), BUCKET_SIDE));
        // Evaluate one cross-section per channel. Taking the deepest of ALL nearby segments
        // lets downstream end caps cut through the banks of an upstream cross-section.
        for (ChannelSegments references : segmentBuckets.getOrDefault(bucket, List.of())) {
            int nearestSegment = -1;
            Projection nearest = null;
            for (int segment : references.segments) {
                Projection projection = distanceToSegment(new Vec2(x, z),
                        references.channel.points().get(segment - 1), references.channel.points().get(segment));
                if (nearest == null || projection.distance < nearest.distance) {
                    nearest = projection; nearestSegment = segment;
                }
            }
            Result result = segmentResult(references.channel, nearestSegment, nearest, x, z, original.groundSurface());
            best = merge(best, result);
        }
        if (original.waterKind() != WaterKind.OCEAN) for (LakeRef lake : lakeBuckets.getOrDefault(bucket, List.of())) {
            Result result = lakeResult(lake, x, z, original.groundSurface());
            best = merge(best, result);
        }
        for (RiverNetwork.Wetland wetland : wetlandBuckets.getOrDefault(bucket, List.of())) {
            if (original.waterKind() == WaterKind.OCEAN) break;
            double distance = distanceToSegment(new Vec2(x + 8 * wetlandWarp.sample(x, z),
                    z + 8 * wetlandWarp.sample(z, x)), wetland.upstream(), wetland.downstream()).distance;
            if (distance >= wetland.radius()) continue;
            double influence = 1.0 - distance * distance / (wetland.radius() * wetland.radius());
            double alpha = smooth(clamp(influence / 0.7)) * clamp((influence - 0.4) / 0.3);
            alpha *= smooth(clamp((original.groundSurface() - wetland.waterSurface()) / 4.0));
            if (alpha <= 0) continue;
            double wetlandBed = wetland.waterSurface() - 1.25;
            double ground = original.groundSurface() + (wetlandBed - original.groundSurface()) * alpha;
            // FTF wetlands mix shallow pools with hummocks; they are not featureless circular lakes.
            double mound = clamp((wetlandMounds.sample(x, z) + 0.25) / 0.6);
            ground += (wetland.waterSurface() + 1.5 - ground) * mound * alpha;
            WaterKind wetlandKind = ground < wetland.waterSurface() ? WaterKind.WETLAND : WaterKind.NONE;
            best = merge(best, new Result(wetland.id(), ground, wetland.waterSurface(), wetlandKind));
        }
        if (best == null) return original;
        WaterKind kind = original.waterKind() == WaterKind.OCEAN ? WaterKind.OCEAN : best.kind;
        double water = kind == WaterKind.OCEAN ? original.waterSurface() : kind == WaterKind.NONE ? Double.NaN : best.water;
        return original.withSurface(StrictMath.min(original.groundSurface(), best.ground), water, kind, original.terrainVersion() + "+" + network.version());
    }

    /** Conservative bank rasterization: retain the edge voxel where floor rounding would open
     * a one-block leak beside a descending water step. Larger defects must be fixed in the graph. */
    public int solidSurfaceAt(int x, int z, MacroSample current) {
        int ground = (int) StrictMath.floor(current.groundSurface());
        if (current.waterKind() == WaterKind.OCEAN || (current.wet()
                && StrictMath.floor(current.waterSurface()) > ground)) return ground;
        long key = bucketKey(Math.floorDiv(x, BUCKET_SIDE), Math.floorDiv(z, BUCKET_SIDE));
        if (!segmentBuckets.containsKey(key) && !lakeBuckets.containsKey(key) && !wetlandBuckets.containsKey(key)) return ground;
        int result = ground;
        for (int side = 0; side < 4; side++) {
            int dx = side == 0 ? 1 : side == 1 ? -1 : 0;
            int dz = side == 2 ? 1 : side == 3 ? -1 : 0;
            MacroSample neighbour = sample(x + dx + 0.5, z + dz + 0.5);
            if (neighbour.wet() && neighbour.waterKind() != WaterKind.OCEAN
                    && StrictMath.floor(neighbour.waterSurface()) > StrictMath.floor(neighbour.groundSurface())) {
                int water = (int) StrictMath.floor(neighbour.waterSurface());
                if (water == ground + 1) result = water;
            }
        }
        return result;
    }

    private static Result merge(Result first, Result second) {
        if (second == null) return first;
        if (first == null) return second;
        // Carving and water ownership are separate. A dry valley must never erase water
        // from a deeper neighbouring channel, nor select a higher water surface by bed depth.
        double ground = StrictMath.min(first.ground, second.ground);
        Result wet = first.kind == WaterKind.NONE ? second : second.kind == WaterKind.NONE ? first
                : StrictMath.abs(first.water - second.water) < 1e-9
                ? (first.ground <= second.ground ? first : second)
                : first.water < second.water ? first : second;
        return new Result(wet.id, ground, wet.water, wet.kind);
    }

    private Result segmentResult(RiverNetwork.Channel channel, int segment, Projection nearest, double x, double z, double original) {
        double water = channel.waterSurfaces().get(segment - 1) + nearest.along
                * (channel.waterSurfaces().get(segment) - channel.waterSurfaces().get(segment - 1));

        var shape = channel.shape();
        Vec2 center = channel.points().get(segment - 1).interpolate(channel.points().get(segment), nearest.along);
        double along = (channel.cumulativeLengths().get(segment - 1) + nearest.along
                * (channel.cumulativeLengths().get(segment) - channel.cumulativeLengths().get(segment - 1))) / channel.length();
        double bedRadius = morphology.bedRadius(channel, along, center.x(), center.z(), x, z);
        double depth = morphology.bedDepth(channel, center.x(), center.z(), bedRadius);
        WaterKind kind = WaterKind.RIVER;
        double bankStepRadius = bedRadius + StrictMath.max(4, shape.minimumBankHeight() * 2);
        double valleyRadius = bankStepRadius + shape.bankWidth();
        double fadeRadius = valleyRadius + shape.bankWidth() * 4.0;
        if (nearest.distance >= fadeRadius) return null;

        double bankTop = water + StrictMath.max(2, shape.minimumBankHeight());
        double valleyFloor = water + shape.maximumBankHeight();
        double ground;
        WaterKind waterKind = WaterKind.NONE;
        if (nearest.distance < bedRadius) {
            double t = smooth(clamp(nearest.distance / bedRadius));
            ground = water - StrictMath.max(2.0, depth) * (1.0 - t);
        } else if (nearest.distance < bankStepRadius) {
            double t = smooth((nearest.distance - bedRadius) / (bankStepRadius - bedRadius));
            ground = water + (bankTop - water) * t;
        } else if (nearest.distance < valleyRadius) {
            double t = smooth((nearest.distance - bankStepRadius) / (valleyRadius - bankStepRadius));
            ground = bankTop + (valleyFloor - bankTop) * t;
        } else {
            double t = smooth((nearest.distance - valleyRadius) / (fadeRadius - valleyRadius));
            ground = valleyFloor + (original - valleyFloor) * t;
        }
        ground = StrictMath.min(original, ground);
        // Fill every submerged part of the section, including the shallow edge.
        if (nearest.distance < bedRadius && ground < water) waterKind = kind;
        return new Result(channel.id() + "/segment/" + segment, StrictMath.min(original, ground), water, waterKind);
    }

    private Result lakeResult(LakeRef lake, double x, double z, double original) {
        double cx = lake.center.x(), cz = lake.center.z();
        // FTF GenWarp.lake: two independent coordinate fields at scales 200 and 50.
        // Center anchoring guarantees that the river meets the lake despite the shoreline warp.
        double wx = x - cx + StrictMath.min(150, lake.radius * 0.45) * (lakeWarpX.sample(x, z) - lakeWarpX.sample(cx, cz))
                + StrictMath.min(25, lake.radius * 0.12) * (lakeFineX.sample(x, z) - lakeFineX.sample(cx, cz));
        double wz = z - cz + StrictMath.min(150, lake.radius * 0.45) * (lakeWarpZ.sample(x, z) - lakeWarpZ.sample(cx, cz))
                + StrictMath.min(25, lake.radius * 0.12) * (lakeFineZ.sample(x, z) - lakeFineZ.sample(cx, cz));
        double along = (wx * lake.dx + wz * lake.dz) / (lake.radius * 1.25);
        double across = (-wx * lake.dz + wz * lake.dx) / (lake.radius * 0.70);
        double distance = StrictMath.hypot(along, across);
        if (distance >= 1.35) return null;
        double ground;
        if (distance < 1) {
            ground = lake.water - lake.depth + (lake.depth + 3) * smooth(distance);
        } else {
            double t = smooth((distance - 1) / 0.35);
            ground = lake.water + 3 + (original - lake.water - 3) * t;
        }
        ground = original + (StrictMath.min(original, ground) - original)
                * smooth(clamp((original - lake.water) / 4.0));
        return new Result(lake.id, ground, lake.water, ground < lake.water ? WaterKind.LAKE : WaterKind.NONE);
    }

    private static Map<Long, List<LakeRef>> indexLakes(RiverNetwork network, MacroTerrain terrain) {
        Map<Long, List<LakeRef>> result = new HashMap<>();
        for (var channel : network.channels()) if (channel.lake() != null) {
            var shape = channel.lake();
            Vec2 center = HydrologyGenerator.pointAt(channel, shape.along());
            Vec2 after = HydrologyGenerator.pointAt(channel, StrictMath.min(1, shape.along() + 0.01));
            double length = center.distance(after);
            double water = HydrologyGenerator.waterAt(channel, shape.along());
            // A level lake may only meet river sections with the same water datum.
            // Reject a frozen feature on a slope instead of overlaying it as a hanging pool.
            double extent = shape.radius() * 2.5;
            if (!compatibleWater(network, center, center, extent, water)) continue;
            LakeRef lake = new LakeRef("lake/" + channel.id(), center, (after.x() - center.x()) / length,
                    (after.z() - center.z()) / length, shape.radius(), shape.depth(), water);
            
            addToBuckets(result, lake, center.x() - extent, center.z() - extent, center.x() + extent, center.z() + extent);
        }
        return freeze(result);
    }

    private record LakeRef(String id, Vec2 center, double dx, double dz, double radius, double depth, double water) {}

    private static Map<Long, List<ChannelSegments>> indexSegments(RiverNetwork network) {
        Map<Long, List<SegmentRef>> result = new HashMap<>();
        for (RiverNetwork.Channel channel : network.channels()) {
            double ordinary = RiverMorphology.maximumBedRadius(channel.shape()) + StrictMath.max(4, channel.shape().minimumBankHeight() * 2)
                    + channel.shape().bankWidth() * 5.0;
            double expansion = ordinary;
            for (int i = 1; i < channel.points().size(); i++) {
                Vec2 a = channel.points().get(i - 1), b = channel.points().get(i);
                SegmentRef reference = new SegmentRef(channel, i);
                addToBuckets(result, reference, StrictMath.min(a.x(), b.x()) - expansion,
                        StrictMath.min(a.z(), b.z()) - expansion, StrictMath.max(a.x(), b.x()) + expansion,
                        StrictMath.max(a.z(), b.z()) + expansion);
            }
        }
        Map<Long, List<ChannelSegments>> grouped = new HashMap<>();
        result.forEach((key, refs) -> {
            Map<RiverNetwork.Channel, List<Integer>> channels = new java.util.LinkedHashMap<>();
            for (var ref : refs) channels.computeIfAbsent(ref.channel, ignored -> new ArrayList<>()).add(ref.segment);
            grouped.put(key, channels.entrySet().stream()
                    .map(entry -> new ChannelSegments(entry.getKey(), List.copyOf(entry.getValue()))).toList());
        });
        return Map.copyOf(grouped);
    }

    private static Map<Long, List<RiverNetwork.Wetland>> indexWetlands(RiverNetwork network) {
        Map<Long, List<RiverNetwork.Wetland>> result = new HashMap<>();
        for (RiverNetwork.Wetland wetland : network.wetlands()) {
            double radius = wetland.radius() + 12;
            if (!compatibleWater(network, wetland.upstream(), wetland.downstream(), radius, wetland.waterSurface())) continue;
            addToBuckets(result, wetland, StrictMath.min(wetland.upstream().x(), wetland.downstream().x()) - radius,
                    StrictMath.min(wetland.upstream().z(), wetland.downstream().z()) - radius,
                    StrictMath.max(wetland.upstream().x(), wetland.downstream().x()) + radius,
                    StrictMath.max(wetland.upstream().z(), wetland.downstream().z()) + radius);
        }
        return freeze(result);
    }

    private static boolean compatibleWater(RiverNetwork network, Vec2 start, Vec2 end, double radius, double water) {
        for (var channel : network.channels()) for (int i = 1; i < channel.points().size(); i++) {
            Vec2 a = channel.points().get(i - 1), b = channel.points().get(i);
            double expansion = radius + RiverMorphology.maximumBedRadius(channel.shape());
            double distance = StrictMath.min(StrictMath.min(distanceToSegment(a, start, end).distance,
                    distanceToSegment(b, start, end).distance), StrictMath.min(distanceToSegment(start, a, b).distance,
                    distanceToSegment(end, a, b).distance));
            if (distance <= expansion && (StrictMath.abs(channel.waterSurfaces().get(i - 1) - water) > 0.15
                    || StrictMath.abs(channel.waterSurfaces().get(i) - water) > 0.15)) return false;
        }
        return true;
    }

    private static <T> void addToBuckets(Map<Long, List<T>> index, T value,
                                         double minX, double minZ, double maxX, double maxZ) {
        int minBucketX = Math.floorDiv((int) StrictMath.floor(minX), BUCKET_SIDE);
        int maxBucketX = Math.floorDiv((int) StrictMath.floor(maxX), BUCKET_SIDE);
        int minBucketZ = Math.floorDiv((int) StrictMath.floor(minZ), BUCKET_SIDE);
        int maxBucketZ = Math.floorDiv((int) StrictMath.floor(maxZ), BUCKET_SIDE);
        for (int bx = minBucketX; bx <= maxBucketX; bx++) for (int bz = minBucketZ; bz <= maxBucketZ; bz++)
            index.computeIfAbsent(bucketKey(bx, bz), ignored -> new ArrayList<>()).add(value);
    }

    private static <T> Map<Long, List<T>> freeze(Map<Long, List<T>> mutable) {
        Map<Long, List<T>> result = new HashMap<>();
        mutable.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static long bucketKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static Projection distanceToSegment(Vec2 point, Vec2 a, Vec2 b) {
        double dx = b.x() - a.x(), dz = b.z() - a.z();
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared == 0 ? 0 : ((point.x() - a.x()) * dx + (point.z() - a.z()) * dz) / lengthSquared;
        t = clamp(t);
        double px = a.x() + t * dx, pz = a.z() + t * dz;
        return new Projection(StrictMath.hypot(point.x() - px, point.z() - pz), t);
    }

    private static double clamp(double value) { return StrictMath.max(0.0, StrictMath.min(1.0, value)); }
    private static double smooth(double value) { return value * value * (3.0 - 2.0 * value); }
    private record Projection(double distance, double along) {}
    private record Result(String id, double ground, double water, WaterKind kind) {}
    private record ChannelSegments(RiverNetwork.Channel channel, List<Integer> segments) {}
    private record SegmentRef(RiverNetwork.Channel channel, int segment) {}
}
