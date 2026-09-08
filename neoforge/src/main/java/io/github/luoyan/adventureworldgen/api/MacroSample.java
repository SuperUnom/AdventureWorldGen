package io.github.luoyan.adventureworldgen.api;

import java.util.Objects;

/** A complete continuous macro-terrain observation at one horizontal coordinate. */
public record MacroSample(double groundSurface, double waterSurface, WaterKind waterKind,
                          boolean hazardous, String regionId, String terrainTemplate,
                          String terrainVersion, String recipe, String secondaryRecipe, double secondaryWeight,
                          double mountainInfluence, double slope, double localRelief, double relativeElevation) {
    public MacroSample(double ground, double water, WaterKind kind, boolean hazardous,
                       String region, String category, String version) {
        this(ground,water,kind,hazardous,region,category,version,
             switch(category) { case "hills" -> "hills_1"; case "mountains" -> "mountains_1"; default -> category; },
             "",0,category.equals("mountains")?1:0,0,0,0);
    }
    public MacroSample withSurface(double ground, double water, WaterKind kind, String version) {
        return new MacroSample(ground,water,kind,hazardous,regionId,terrainTemplate,version,
            recipe,secondaryRecipe,secondaryWeight,mountainInfluence,slope,localRelief,relativeElevation);
    }
    public MacroSample withMorphology(double slope, double relief, double relative) {
        return new MacroSample(groundSurface,waterSurface,waterKind,hazardous,regionId,terrainTemplate,terrainVersion,
            recipe,secondaryRecipe,secondaryWeight,mountainInfluence,slope,relief,relative);
    }
    public String landform() {
        if(mountainInfluence < 0.15) return "lowland";
        if(localRelief >= 22 && relativeElevation >= 8 && slope >= 0.12 && groundSurface >= 120) return "peak";
        if(mountainInfluence < 0.55 || (localRelief < 22 && slope < 0.28)) return "foothill";
        return "slope";
    }
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
        Objects.requireNonNull(recipe, "recipe");Objects.requireNonNull(secondaryRecipe, "secondaryRecipe");
        if(!Double.isFinite(secondaryWeight)||secondaryWeight<0||secondaryWeight>1
            ||!Double.isFinite(mountainInfluence)||mountainInfluence<0||mountainInfluence>1.000000001
            ||!Double.isFinite(slope)||slope<0||!Double.isFinite(localRelief)||localRelief<0||!Double.isFinite(relativeElevation))
            throw new IllegalArgumentException("invalid terrain recipe/morphology observation");
    }

    public boolean wet() { return waterKind != WaterKind.NONE; }
    public double waterDepth() { return wet() ? StrictMath.max(0.0, waterSurface - groundSurface) : 0.0; }
    public double travelSurface() { return wet() ? waterSurface : groundSurface; }
}
