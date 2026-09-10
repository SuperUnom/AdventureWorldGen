package io.github.luoyan.adventureworldgen.compat.vanilla;

import io.github.luoyan.adventureworldgen.plan.ContentId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the v1 default water-biome mapping. The list is a product decision, so a change to it has to
 * be a deliberate edit here plus the matching verification, not a silent rewrite of the mapping.
 */
class VanillaWaterBiomesTest {
    @Test
    void coldLandUsesTheFrozenRiverAndEverythingElseTheRiver() {
        for (String land : new String[]{"minecraft:snowy_plains", "minecraft:snowy_taiga",
                "minecraft:ice_spikes", "minecraft:grove", "minecraft:snowy_slopes",
                "minecraft:jagged_peaks", "minecraft:frozen_peaks", "minecraft:snowy_beach"}) {
            assertEquals(VanillaWaterBiomes.FROZEN_RIVER,
                    VanillaWaterBiomes.inlandWaterFor(new ContentId(land)), land);
        }
        for (String land : new String[]{"minecraft:plains", "minecraft:forest", "minecraft:desert",
                "minecraft:taiga", "minecraft:windswept_hills", "testcompanion:ashen_grove"}) {
            assertEquals(VanillaWaterBiomes.RIVER,
                    VanillaWaterBiomes.inlandWaterFor(new ContentId(land)), land);
        }
    }

    @Test
    void theDefaultListIsTheOnlyColdSource() {
        // Taiga and windswept hills look cold but are not in the confirmed list: the mapping is an
        // id list, not a temperature inference, and this test fails if someone turns it into one.
        assertTrue(VanillaWaterBiomes.coldLandBiomes().size() == 8,
                "the confirmed default list changed: " + VanillaWaterBiomes.coldLandBiomes());
        assertTrue(!VanillaWaterBiomes.coldLandBiomes().contains("minecraft:taiga"));
        assertTrue(!VanillaWaterBiomes.coldLandBiomes().contains("minecraft:windswept_hills"));
    }
}
