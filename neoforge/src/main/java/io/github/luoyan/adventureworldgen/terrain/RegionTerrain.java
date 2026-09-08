package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.planner.DeterministicRandom;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import io.github.luoyan.adventureworldgen.spatial.Vec2;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Infinite, world-aligned jittered Voronoi regions with continuous shared-boundary blending. */
public final class RegionTerrain {
    public enum Template { PLAINS, HILLS, PLATEAU, MOUNTAINS }

    private final long seed;
    private final String algorithmVersion;
    private final PlannerProfile.Terrain profile;
    private final ValueNoise warpX;
    private final ValueNoise warpZ;
    private final ValueNoise base;
    private final MountainTerrain mountains;
    private final EcotoneNoise templateEcotone;
    private static final Template[] TEMPLATES = Template.values();
    private final Map<GridKey, Region> regions = new ConcurrentHashMap<>();
    private final GridKey centralRegion;
    private final TerrainCapacityPlan capacities;

    public RegionTerrain(long seed, PlannerProfile plannerProfile) {
        this(seed,plannerProfile,TerrainCapacityPlan.empty());
    }
    public RegionTerrain(long seed, PlannerProfile plannerProfile,TerrainCapacityPlan capacities) {
        this.capacities=capacities;
        this.seed = seed;
        this.algorithmVersion = plannerProfile.algorithmVersion();
        this.profile = plannerProfile.terrain();
        this.warpX = new ValueNoise(seed, "region-warp/x", profile.coordinateWarpScale());
        this.warpZ = new ValueNoise(seed, "region-warp/z", profile.coordinateWarpScale());
        this.mountains = new MountainTerrain(seed);
        this.templateEcotone = new EcotoneNoise(seed, "terrain/biome-ecotone-r10", 40);
        this.base = new ValueNoise(seed, "terrain/base", 1024);
        this.centralRegion = nearestPair(warped(0, 0)).nearest.key;
        // It was provisionally created before the central key was known.
        regions.remove(centralRegion);
    }

    public Sample sample(double x, double z) {
        Vec2 query = warped(x, z);
        Pair pair = nearestPair(query);
        double ratio = pair.nearest.distance / pair.second.distance;
        double t = clamp((1.0 - ratio) / 0.35);
        double internalWeight = smooth(t);
        double commonBase = 24.0 + 6.0 * base.sample(x, z);
        // Blend neighboring templates directly: a shared low boundary made every region a mound.
        double sum = 0, weights = 0;
        double[] templateDistances = new double[TEMPLATES.length];
        java.util.Arrays.fill(templateDistances, Double.POSITIVE_INFINITY);
        long gx = fastFloor(query.x() / profile.regionSpacing()), gz = fastFloor(query.z() / profile.regionSpacing());
        for (long rx = gx - 2; rx <= gx + 2; rx++) for (long rz = gz - 2; rz <= gz + 2; rz++) {
            Region region = region(new GridKey(rx, rz));
            double distance = query.distance(region.center);
            templateDistances[region.template.ordinal()] = StrictMath.min(templateDistances[region.template.ordinal()], distance);
            double w = smooth(clamp(1 - (distance - pair.nearest.distance) / 220.0));
            if (w > 0) {
                sum += w * templateHeight(region, commonBase, x, z); weights += w;
            }
        }
        double height = sum / weights;
        // Broad height blending must not turn the entire slope into a multi-template mosaic.
        // Eligibility only interleaves near the two closest template regions; height is unchanged.
        Template biomeTemplate = TEMPLATES[EcotoneSelector.select(templateDistances,
                templateEcotone.threshold(x, z), 48)];
        return new Sample(height, pair.nearest.region.id, biomeTemplate,
                internalWeight, pair.nearest.distance, pair.second.distance);
    }

    public Region region(long gridX, long gridZ) {
        return region(new GridKey(gridX, gridZ));
    }
    public GridKey regionKeyAt(double x,double z) { return nearestPair(warped(x,z)).nearest.key; }
    public GridKey interiorRegionAt(double x,double z,double margin) {
        var pair=nearestPair(warped(x,z));
        return pair.second.distance-pair.nearest.distance>=margin?pair.nearest.key:null;
    }

    private double templateHeight(Region region, double commonBase, double x, double z) {
        double f;
        double wx = x + 90 * warpX.sample(x * 1.7, z * 1.7);
        double wz = z + 90 * warpZ.sample(x * 1.7, z * 1.7);
        double height = switch (region.template) {
            case PLAINS -> commonBase + 6.0 * fractal(region.noise512, wx, wz);
            case HILLS -> commonBase + 30.0 + 30.0 * fractal(region.noise512, wx, wz);
            case PLATEAU -> {
                f = fractal(region.noise768, wx, wz);
                yield commonBase + 70.0 * smooth(clamp((f + 0.3) / 0.6));
            }
            case MOUNTAINS -> {
                yield commonBase + mountains.sample(x, z);
            }
        };
        var reservation=capacities.at(region.key);
        if(reservation!=null) {
            // Erosion delta is bounded to [-12,+8]. Coast blending and water are checked again on final cells.
            if(reservation.minHeight()!=null)height=StrictMath.max(height,reservation.minHeight()+12-64);
            if(reservation.maxHeight()!=null)height=StrictMath.min(height,reservation.maxHeight()-8-64);
        }
        return height;
    }

    private Pair nearestPair(Vec2 query) {
        int spacing = profile.regionSpacing();
        long baseX = fastFloor(query.x() / spacing), baseZ = fastFloor(query.z() / spacing);
        Candidate nearest = null, second = null;
        for (int radius = 1; ; radius++) {
            for (long gx = baseX - radius; gx <= baseX + radius; gx++) {
                for (long gz = baseZ - radius; gz <= baseZ + radius; gz++) {
                    if (radius > 1 && gx > baseX - radius && gx < baseX + radius
                            && gz > baseZ - radius && gz < baseZ + radius) continue;
                    Region region = region(new GridKey(gx, gz));
                    double distance = query.distance(region.center);
                    Candidate candidate = new Candidate(region.key, region, distance);
                    if (nearest == null || ORDER.compare(candidate, nearest) < 0) {
                        second = nearest; nearest = candidate;
                    } else if (second == null || ORDER.compare(candidate, second) < 0) {
                        second = candidate;
                    }
                }
            }
            double unsearchedLowerBound = StrictMath.max(0.0,
                    (radius - profile.maximumRegionJitterFraction()) * spacing);
            if (second != null && second.distance < unsearchedLowerBound) return new Pair(nearest, second);
            if (radius > 16) throw new IllegalStateException("region nearest-neighbor proof failed");
        }
    }

    private Region region(GridKey key) {
        return regions.computeIfAbsent(key, ignored -> createRegion(key));
    }

    private Region createRegion(GridKey key) {
        int spacing = profile.regionSpacing();
        double jitter = spacing * profile.maximumRegionJitterFraction();
        String id = "region/" + key.x + "/" + key.z;
        double x = key.x * (double) spacing + signedSample(id, 0) * jitter;
        double z = key.z * (double) spacing + signedSample(id, 1) * jitter;
        Template template;
        if (capacities.at(key)!=null) {
            template=capacities.at(key).template();
        } else if (key.equals(centralRegion)) {
            template = Template.PLAINS;
        } else {
            double choice = DeterministicRandom.sample(seed, algorithmVersion, "region-template", id, 0) * 100.0;
            template = choice < 35 ? Template.PLAINS : choice < 70 ? Template.HILLS
                    : choice < 85 ? Template.PLATEAU : Template.MOUNTAINS;
        }
        return new Region(key, id, new Vec2(x, z), template,
                fractalFields(seed, id + "/512", 512), fractalFields(seed, id + "/768", 768));
    }

    private double signedSample(String id, long index) {
        return DeterministicRandom.sample(seed, algorithmVersion, "region-center", id, index) * 2.0 - 1.0;
    }

    private Vec2 warped(double x, double z) {
        return new Vec2(x + profile.coordinateWarpAmplitude() * warpX.sample(x, z),
                z + profile.coordinateWarpAmplitude() * warpZ.sample(x, z));
    }

    private static ValueNoise[] fractalFields(long seed, String id, int wavelength) {
        return new ValueNoise[] { new ValueNoise(seed, id + "/0", wavelength),
                new ValueNoise(seed, id + "/1", wavelength / 2.0),
                new ValueNoise(seed, id + "/2", wavelength / 4.0) };
    }

    private static double fractal(ValueNoise[] fields, double x, double z) {
        return (fields[0].sample(x, z) + 0.5 * fields[1].sample(x, z)
                + 0.25 * fields[2].sample(x, z)) / 1.75;
    }

    private static double clamp(double value) { return StrictMath.max(0.0, StrictMath.min(1.0, value)); }
    private static double smooth(double value) { return value * value * (3.0 - 2.0 * value); }
    private static long fastFloor(double value) { long i = (long) value; return value < i ? i - 1 : i; }

    private static final Comparator<Candidate> ORDER = Comparator.comparingDouble(Candidate::distance)
            .thenComparing(candidate -> candidate.region.id);

    public record Sample(double relativeHeight, String regionId, Template template, double internalWeight,
                         double nearestDistance, double secondDistance) {}
    public record Region(GridKey key, String id, Vec2 center, Template template,
                         ValueNoise[] noise512, ValueNoise[] noise768) {
        public Region { noise512 = noise512.clone(); noise768 = noise768.clone(); }
        @Override public ValueNoise[] noise512() { return noise512.clone(); }
        @Override public ValueNoise[] noise768() { return noise768.clone(); }
    }
    public record GridKey(long x, long z) {}
    private record Candidate(GridKey key, Region region, double distance) {}
    private record Pair(Candidate nearest, Candidate second) {}
}
