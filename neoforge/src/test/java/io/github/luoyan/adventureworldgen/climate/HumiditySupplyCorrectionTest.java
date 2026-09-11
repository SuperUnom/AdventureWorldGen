package io.github.luoyan.adventureworldgen.climate;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the actual admission range of the humidity supply mask, because the class comment used to
 * suggest a per-position decision and the earlier reading of the code claimed a same-key /
 * different-height mistake.
 *
 * <p>The correction excludes height-limited and shore-only filler rules before it builds the mask,
 * so every remaining admission condition is part of the cache key. These tests hold that line:
 * same key means same mask regardless of height or query order, height-limited and shore-only rules
 * contribute nothing, and no contribution at all returns the natural value.
 */
class HumiditySupplyCorrectionTest {
    private static MacroSample land(double height) {
        // mountainInfluence 0 keeps landform()=="lowland" for every height, so these samples share
        // one supply key and differ only in ground surface.
        return new MacroSample(height, Double.NaN, WaterKind.NONE, false, "r", "plains", "test");
    }

    private static AdventureWorldConfig config(String filler, String terrainRules) {
        return new AdventureWorldConfigParser().parse("""
                {"world":{"radius":1024},"spawn":{"biome":"test:ordinary"},"biomes":{
                  "filler":[""" + filler + """
                ],"terrain_rules":{""" + terrainRules + """
                }}}
                """);
    }

    private static final String DRY_ONLY = """
            "test:desert":{"humidities":{"dry":1}}""";

    @Test
    void sameKeyDifferentHeightsAndSwappedQueryOrderGiveTheSameValue() {
        var correction = new HumiditySupplyCorrection(config("\"test:desert\"", DRY_ONLY));
        // 0.9 is the wet band; the only filler accepts dry, so the value snaps to the dry edge.
        double low = correction.correct(0.9, land(66));
        double high = correction.correct(0.9, land(180));
        assertEquals(low, high, "the mask must not depend on the height that created the key");
        assertNotEquals(0.9, low, "a single-band filler pool must pull the value into that band");
        assertEquals(0.379999, low, 1e-12);

        // Reversing the order in a fresh instance must not change either result.
        var reversed = new HumiditySupplyCorrection(config("\"test:desert\"", DRY_ONLY));
        assertEquals(high, reversed.correct(0.9, land(180)));
        assertEquals(low, reversed.correct(0.9, land(66)));
        // An already-feasible value is returned untouched, and stays untouched on a repeat query.
        assertEquals(0.2, correction.correct(0.2, land(66)));
        assertEquals(0.2, correction.correct(0.2, land(66)));
    }

    @Test
    void heightLimitedAndShoreOnlyRulesDoNotReachTheMask() {
        double baseline = new HumiditySupplyCorrection(config("\"test:desert\"", DRY_ONLY))
                .correct(0.9, land(66));

        // A wet-only filler with a height window. The position is inside the window, so a
        // position-level rule would add the wet band and leave 0.9 alone; the supply correction
        // excludes it by construction, so the dry snap must survive.
        var withHeightLimit = new HumiditySupplyCorrection(config(
                "\"test:desert\",\"test:snowy\"",
                DRY_ONLY + ",\"test:snowy\":{\"min_height\":40,\"max_height\":200,\"humidities\":{\"wet\":1}}"));
        assertEquals(baseline, withHeightLimit.correct(0.9, land(66)),
                "a height-limited filler changed the template-level supply mask");

        // Same for a shore-only filler, which is equally a position decision.
        var withShoreOnly = new HumiditySupplyCorrection(config(
                "\"test:desert\",\"test:beach\"",
                DRY_ONLY + ",\"test:beach\":{\"shore_only\":true,\"humidities\":{\"wet\":1}}"));
        assertEquals(baseline, withShoreOnly.correct(0.9, land(66)),
                "a shore-only filler changed the template-level supply mask");
    }

    @Test
    void aFillerWithoutHumidityPreferencesAdmitsEveryBand() {
        // "no preference" is represented as the full mask, so the natural value survives.
        var correction = new HumiditySupplyCorrection(config("\"test:plain\"", """
                "test:plain":{}"""));
        for (double value : new double[]{0.05, 0.5, 0.95}) {
            assertEquals(value, correction.correct(value, land(66)));
        }
    }

    @Test
    void noSupplyingFillerLeavesTheNaturalValueAlone() {
        // Two fillers whose allowed recipe sets do not overlap, between them covering every enabled
        // template so the profile stays valid. A sample that mixes one filler's primary recipe with
        // the other's is accepted by neither, so no filler supplies a band: mask == 0 and the
        // natural value must survive untouched.
        var correction = new HumiditySupplyCorrection(config(
                "\"test:dry\",\"test:other\"",
                """
                "test:dry":{"allowed_templates":["plains","steppe"],"humidities":{"dry":1}},
                "test:other":{"allowed_templates":["hills_1","hills_2","dales","torridonian","plateau",
                  "badlands","mountains_1","mountains_2","mountains_3","volcano"],"humidities":{"wet":1}}"""));
        var mixed = new MacroSample(66, Double.NaN, WaterKind.NONE, false, "r", "plains", "test",
                "plains", "hills_1", 0.5, 0, 0, 0, 0);
        assertEquals(0.9, correction.correct(0.9, mixed));
        assertEquals(0.05, correction.correct(0.05, mixed));
        // The same instance still snaps a sample that one filler does accept.
        assertEquals(0.379999, correction.correct(0.9, land(66)), 1e-12);
    }

    @Test
    void everyEnabledTemplateMustHaveAnUnrestrictedFiller() {
        // The parser guarantees supply coverage, which is the reason an empty mask needs a sample
        // that no filler accepts - not a configuration that no filler covers.
        assertThrows(io.github.luoyan.adventureworldgen.config.ConfigException.class,
                () -> config("\"test:highland\"", """
                "test:highland":{"min_height":200,"humidities":{"dry":1}}"""));
    }
}
