package io.github.luoyan.adventureworldgen.erosion;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;

/** Runtime query wrapper; it never re-runs the droplet simulation. */
public final class ErodedTerrain implements MacroTerrain {
    private final MacroTerrain base;
    private final ErosionDeltaField delta;
    private final String version;

    public ErodedTerrain(MacroTerrain base, ErosionDeltaField delta, String version) {
        this.base = base; this.delta = delta; this.version = version;
    }

    @Override public MacroSample sample(double x, double z) {
        MacroSample sample = base.sample(x, z);
        if (sample.wet()) return sample;
        // Fade erosion out near sea level, preserving the coastline and dry coastal land.
        double coastal = StrictMath.max(0.0, StrictMath.min(1.0, (sample.groundSurface() - 64.0) / 16.0));
        double ground = sample.groundSurface() + delta.sample(x, z) * coastal * coastal * (3.0 - 2.0 * coastal);
        return new MacroSample(ground, sample.waterSurface(), sample.waterKind(), sample.hazardous(),
                sample.regionId(), sample.terrainTemplate(), sample.terrainVersion() + "+" + version);
    }
}
