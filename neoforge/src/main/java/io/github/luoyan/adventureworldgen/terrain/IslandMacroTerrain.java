package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;

import java.util.Objects;

/** Coast and regional terrain composition before inland hydrology is applied. */
public final class IslandMacroTerrain implements MacroTerrain {
    private final Coastline coastline;
    private final RegionTerrain regions;
    private final double seaSurface;
    private final double landBand;
    private final double seaBand;
    private final ValueNoise deepOcean;
    private final String version;

    public IslandMacroTerrain(Coastline coastline, RegionTerrain regions, long seed, double seaSurface,
                              double landBand, double seaBand, String version) {
        this.coastline = Objects.requireNonNull(coastline, "coastline");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.seaSurface = seaSurface;
        this.landBand = landBand;
        this.seaBand = seaBand;
        this.deepOcean = new ValueNoise(seed, "ftf-adapted/deep-ocean", 600);
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public MacroSample sample(double x, double z) {
        double signedDistance = coastline.signedDistance(x, z);
        RegionTerrain.Sample region = regions.sample(x, z);
        if (signedDistance >= 0.0) {
            double blend = smooth(clamp(signedDistance / landBand));
            double relative = StrictMath.max(0.0, region.relativeHeight());
            return new MacroSample(seaSurface + blend * relative, Double.NaN, WaterKind.NONE, false,
                    region.regionId(), region.template().name().toLowerCase(), version);
        }
        double blend = smooth(clamp(-signedDistance / seaBand));
        // FTF-adapted deep-water envelope; the first seven blocks remain the fixed shallow shelf.
        double deep = 7.0 + 25.0 * clamp((deepOcean.sample(x, z) + 1.0) * 0.5);
        return new MacroSample(seaSurface - blend * deep, seaSurface, WaterKind.OCEAN, false,
                region.regionId(), region.template().name().toLowerCase(), version);
    }

    private static double clamp(double value) { return StrictMath.max(0.0, StrictMath.min(1.0, value)); }
    private static double smooth(double value) { return value * value * (3.0 - 2.0 * value); }
}
