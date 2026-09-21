package io.github.luoyan.adventureworldgen.config;

import io.github.luoyan.adventureworldgen.plan.ContentId;

/** Author intent and bounded construction parameters, all included in canonical input. */
public record RoadSettings(boolean enabled, int width, double maximumGrade, int maximumEarthwork,
                           int maximumBridgeLength, int clearance, double bendSpacing, double bendAmplitude,
                           double maximumBendDetour, double loopBudgetFraction, int maximumNodes,
                           long maximumOperations, int maximumColumns, String surface, String bridge,
                           String foundation) {
    public static RoadSettings disabled() {
        return new RoadSettings(false, 3, .35, 3, 32, 3, 120, 14, 1.1, .15,
                96, 8_000_000, 500_000, "minecraft:gravel", "minecraft:oak_planks", "minecraft:cobblestone");
    }
    public RoadSettings {
        if (width < 3 || width > 9 || width % 2 == 0 || !Double.isFinite(maximumGrade)
                || maximumGrade <= 0 || maximumGrade > .5 || maximumEarthwork < 0 || maximumEarthwork > 8
                || maximumBridgeLength < 0 || maximumBridgeLength > 64 || clearance < 3 || clearance > 8
                || !Double.isFinite(bendSpacing) || bendSpacing < 40 || bendSpacing > 320
                || !Double.isFinite(bendAmplitude) || bendAmplitude < 0 || bendAmplitude > 32
                || !Double.isFinite(maximumBendDetour) || maximumBendDetour < 1 || maximumBendDetour > 1.25
                || !Double.isFinite(loopBudgetFraction) || loopBudgetFraction < 0 || loopBudgetFraction > .5
                || maximumNodes < 2 || maximumNodes > 256 || maximumOperations < 1 || maximumOperations > 100_000_000
                || maximumColumns < 1 || maximumColumns > 2_000_000)
            throw new IllegalArgumentException("invalid road geometry or resource budget");
        new ContentId(surface); new ContentId(bridge); new ContentId(foundation);
    }
}
