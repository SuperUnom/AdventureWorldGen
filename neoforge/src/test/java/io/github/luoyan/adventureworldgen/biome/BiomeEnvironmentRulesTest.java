package io.github.luoyan.adventureworldgen.biome;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules;
import io.github.luoyan.adventureworldgen.climate.ClimatePlan;

/**
 * The shared rules service is what every stage asks, so its answers must be the conjunction of its
 * components and must follow the author's temperature restriction exactly. This is the property the
 * P2 exit condition depends on: seeding, growth, filler and transition cannot disagree about
 * admission because they all resolve it here.
 */
class BiomeEnvironmentRulesTest {
    private static final MacroTerrain FLAT = (x, z) ->
            new MacroSample(80, Double.NaN, WaterKind.NONE, false, "test", "plains", "test");
    private static final ContentId HOT_ONLY = new ContentId("test:hot_only");

    private static AdventureWorldConfig config() {
        return new AdventureWorldConfigParser().parse("""
                {"world":{"radius":512},"spawn":{"biome":"test:plains"},"biomes":{
                 "filler":["test:plains"],
                 "terrain_rules":{"test:hot_only":{"temperatures":{"hot":1}}}}}
                """);
    }

    @Test
    void admissionIsExactlyTheConjunctionOfItsComponentsOnTheOwnershipGrid() {
        var config = config();
        var rules = new BiomeEnvironmentRules(config, new ClimatePlan(7331, config, FLAT,new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(config,ClimatePlan.STEP)));
        int admitted = 0, rejected = 0;
        for (int x = -480; x <= 480; x += 16) for (int z = -480; z <= 480; z += 16) {
            var sample = FLAT.sample(x, z);
            // Admission is decided on the final 4-block ownership grid centre, so the component
            // checks must be asked at the same quantized coordinate the service uses.
            int qx = Math.floorDiv(x, 4) * 4 + 2, qz = Math.floorDiv(z, 4) * 4 + 2;
            boolean viaService = rules.allows(HOT_ONLY, x, z, sample);
            boolean viaComponents = rules.prefersType(HOT_ONLY, qx, qz, sample)
                    && rules.humidity().allows(HOT_ONLY, qx, qz, sample);
            assertEquals(viaComponents, viaService,
                    "the shared admission must agree with its components at " + x + "," + z);
            if (viaService) admitted++; else rejected++;
        }
        assertTrue(admitted > 0, "the accepted field must admit a hot-only biome somewhere");
        assertTrue(rejected > 0, "a hot-only biome must be rejected outside its bands");
    }

    @Test
    void restrictedTemperatureBiomeIsAdmittedOnlyAtDistanceZero() {
        var config = config();
        var rules = new BiomeEnvironmentRules(config, new ClimatePlan(7331, config, FLAT,new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(config,ClimatePlan.STEP)));
        for (int x = -480; x <= 480; x += 32) for (int z = -480; z <= 480; z += 32) {
            var sample = FLAT.sample(x, z);
            int distance = rules.temperatureDistance(HOT_ONLY, x, z, sample);
            assertEquals(rules.prefersType(HOT_ONLY, x, z, sample), distance == 0,
                    "preference must match a zero band distance at " + x + "," + z);
        }
    }

    @Test
    void shoreOnlyAdmissionIsNotAFreePassOnInlandTerrain() {
        // The service owns the shore-only rule; a land-only terrain has no shore, so the biome must
        // never be admitted there. (The positive ocean-shore case is covered by HumidityPlanTest.)
        var config = new AdventureWorldConfigParser().parse("""
                {"world":{"radius":512},"spawn":{"biome":"test:plains"},"biomes":{
                 "filler":["test:plains","test:beach"],
                 "terrain_rules":{"test:beach":{"shore_only":true}}}}
                """);
        var rules = new BiomeEnvironmentRules(config, new ClimatePlan(7331, config, FLAT,new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(config,ClimatePlan.STEP)));
        var beach = new ContentId("test:beach");
        for (int x = -480; x <= 480; x += 16) for (int z = -480; z <= 480; z += 16) {
            var sample = FLAT.sample(x, z);
            assertFalse(rules.humidity().isShore(x, z, sample), "flat dry terrain has no shore");
            assertFalse(rules.allows(beach, x, z, sample), "shore-only biome admitted inland at " + x + "," + z);
        }
    }
}
