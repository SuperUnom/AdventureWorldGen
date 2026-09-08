package io.github.luoyan.adventureworldgen.api;

import java.util.Objects;

/** A complete continuous macro-terrain observation at one horizontal coordinate. */
public record MacroSample(double groundSurface, double waterSurface, WaterKind waterKind,
                          boolean hazardous, String regionId, String terrainTemplate,
                          String terrainVersion) {
    public MacroSample {
        if (!Double.isFinite(groundSurface)) throw new IllegalArgumentException("groundSurface must be finite");
        if (waterKind == WaterKind.NONE && !Double.isNaN(waterSurface))
            throw new IllegalArgumentException("dry samples use NaN waterSurface");
        if (waterKind != WaterKind.NONE && !Double.isFinite(waterSurface))
            throw new IllegalArgumentException("wet samples require a finite waterSurface");
        Objects.requireNonNull(waterKind, "waterKind");
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(terrainTemplate, "terrainTemplate");
        Objects.requireNonNull(terrainVersion, "terrainVersion");
    }

    public boolean wet() { return waterKind != WaterKind.NONE; }
    public double waterDepth() { return wet() ? StrictMath.max(0.0, waterSurface - groundSurface) : 0.0; }
    public double travelSurface() { return wet() ? waterSurface : groundSurface; }
}
