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
    private static List<io.github.luoyan.adventureworldgen.climate.ClimateField.Site> sites() {
        List<io.github.luoyan.adventureworldgen.climate.ClimateField.Site> sites = new ArrayList<>();
        int[] bands = {0, 0, 1, 1, 2, 2, 3, 3};
        for (int i = 0; i < bands.length; i++) sites.add(new io.github.luoyan.adventureworldgen.climate.ClimateField.Site(i * 32, 0, land()));
        return sites;
    }

    /**
     * Band comes from the site order only.
     *
     * <p>The permutation is deliberately not a threshold ladder over any monotone value: {@code
     * 3,1,0,2,2,0,1,3} cannot be produced by splitting a single scalar field at three cut points.
     * The interface offers no raw value to reconstruct a band from, so a diagnostic that still
     * guessed at one would disagree with this field.
     */
    private static final int[] BANDS = {3, 1, 0, 2, 2, 0, 1, 3};
    private static final io.github.luoyan.adventureworldgen.climate.ClimateField FIELD = new io.github.luoyan.adventureworldgen.climate.ClimateField() {
        public int band(int x, int z, MacroSample sample) { return BANDS[Math.floorDiv(x, 32)]; }
    };

    /** The histogram of the permuted band array, which every diagnostic must reproduce. */
    private static double[] expectedBandShares() {
        double[] shares = new double[4];
        for (int band : BANDS) shares[band] += 1.0 / BANDS.length;
        return shares;
    }

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
        double[] actual = diagnostics().actualRatios(FIELD, sites());
        double[] expected = expectedBandShares();
        assertEquals(4, actual.length);
        for (int band = 0; band < 4; band++)
            assertEquals(expected[band], actual[band], 1e-12, "band " + band);
        assertEquals(1.0, java.util.Arrays.stream(actual).sum(), 1e-12);
    }

    @Test
    void targetRatiosFollowTheFieldsOwnBandClassification() {
        // An unrestricted filler biome prefers all four bands equally, so the demand spread is the
        // band histogram exactly. A diagnostic that reconstructed bands from a scalar value could
        // not produce this shape for the permuted field above.
        double[] target = diagnostics().targetRatios(FIELD, sites());
        for (int band = 0; band < 4; band++)
            assertEquals(expectedBandShares()[band], target[band], 0.06,
                    "band " + band + " drifted off the field's bands");
        assertEquals(1.0, java.util.Arrays.stream(target).sum(), 1e-12);
    }

    @Test
    void supplyFollowsTheFieldsOwnBandClassification() {
        // The unrestricted biome prefers every band, so a supply entry's climate area is the whole
        // legal area only if the classification reads the same band histogram the field reports.
        var supply = diagnostics().supply(FIELD, sites());
        long allSites = (long) sites().size() * STEP * STEP;
        for (var item : supply) {
            assertEquals(allSites, item.legalArea(), item.biome());
            assertEquals(allSites, item.climateArea(), item.biome());
        }
    }

    @Test
    void targetRatiosAreNormalizedAuthorDemand() {
        double[] target = diagnostics().targetRatios(FIELD, sites());
        assertEquals(4, target.length);
        assertEquals(1.0, java.util.Arrays.stream(target).sum(), 1e-12, "target ratios must be normalized");
        for (double share : target) assertTrue(share >= 0, "a band share cannot be negative");
    }

    @Test
    void supplyAccountsLegalAndClimateAreaPerRequiredPatch() {
        var supply = diagnostics().supply(FIELD, sites());
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
