package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The climate demand/supply statistics are diagnostics: they must be verifiable without building a
 * real plan, and they must not depend on field construction to be correct. This exercises them
 * against a synthetic site list and a synthetic temperature field, so the expected numbers are
 * stated directly rather than copied from production output.
 */
class ClimateDiagnosticsTest {
    private static final int STEP = 4;

    private static MacroSample land() {
        return new MacroSample(80, Double.NaN, WaterKind.NONE, false, "test", "plains", "test");
    }

    /** Eight sites with exactly two in each temperature band. */
    private static List<ClimateDiagnostics.Site> sites() {
        List<ClimateDiagnostics.Site> sites = new ArrayList<>();
        int[] bands = {0, 0, 1, 1, 2, 2, 3, 3};
        for (int i = 0; i < bands.length; i++) sites.add(new ClimateDiagnostics.Site(i * 32, 0, land()));
        return sites;
    }

    /** Band is read from the site order; value sits inside that band. */
    private static final int[] BANDS = {0, 0, 1, 1, 2, 2, 3, 3};
    private static final double[] VALUES = {1.0, 3.5, 6.0, 8.5};
    private static final ClimateDiagnostics.TemperatureField FIELD = new ClimateDiagnostics.TemperatureField() {
        public int band(int x, int z, MacroSample sample) { return BANDS[Math.floorDiv(x, 32)]; }
        public double value(int x, int z, MacroSample sample) { return VALUES[band(x, z, sample)]; }
    };

    private static ClimateDiagnostics diagnostics() {
        var config = new AdventureWorldConfigParser().parse("""
                {"world":{"radius":512},"spawn":{"biome":"minecraft:plains"},"biomes":{
                 "required":[{"id":"minecraft:forest","adventure_level":3,"area":{"min":4096,"max":8192}}],
                 "filler":["minecraft:plains"]}}
                """);
        return new ClimateDiagnostics(config, STEP);
    }

    @Test
    void actualRatiosAreTheExactBandHistogramOfTheSites() {
        double[] actual = diagnostics().actualRatios(sites(), FIELD);
        assertEquals(4, actual.length);
        for (int band = 0; band < 4; band++) assertEquals(0.25, actual[band], 1e-12, "band " + band);
        assertEquals(1.0, java.util.Arrays.stream(actual).sum(), 1e-12);
    }

    @Test
    void targetRatiosAreNormalizedAuthorDemand() {
        double[] target = diagnostics().targetRatios(sites(), FIELD);
        assertEquals(4, target.length);
        assertEquals(1.0, java.util.Arrays.stream(target).sum(), 1e-12, "target ratios must be normalized");
        for (double share : target) assertTrue(share >= 0, "a band share cannot be negative");
    }

    @Test
    void supplyAccountsLegalAndClimateAreaPerRequiredPatch() {
        var supply = diagnostics().supply(sites(), FIELD);
        // The explicit required biome and the implicit spawn biome each get one entry.
        assertEquals(2, supply.size());
        var entry = supply.stream().filter(s -> s.biome().equals(new ContentId("minecraft:forest").value()))
                .findFirst().orElseThrow();
        assertEquals(8192, entry.target(), "target area comes from the author's demand");
        // Every site has no terrain rule, and an unrestricted biome prefers all four bands.
        long allSites = (long) sites().size() * STEP * STEP;
        assertEquals(allSites, entry.legalArea());
        assertEquals(allSites, entry.climateArea());
        for (var item : supply)
            assertTrue(item.climateArea() <= item.legalArea(), "climate area cannot exceed legal area");
    }
}
