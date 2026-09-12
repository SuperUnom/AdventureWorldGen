package io.github.luoyan.adventureworldgen.compat.vanilla;

import io.github.luoyan.adventureworldgen.plan.ContentId;

import java.util.Set;

/**
 * The default rule for which water biome a land biome's rivers and lakes use.
 *
 * <p>This is the confirmed v1 default mapping, moved out of the plan object so the content rule has
 * one named home. It is <b>not</b> a climate inference: only the ids listed below count as cold, and
 * every other land biome uses the temperate river. Tags, temperature-band inference, per-biome
 * author declarations and datapack overrides are deliberately out of scope - adding any of them is
 * a new capability that needs its own decision and its own verification, not a rewrite of this list.
 */
public final class VanillaWaterBiomes {
    public static final ContentId RIVER = new ContentId("minecraft:river");
    public static final ContentId FROZEN_RIVER = new ContentId("minecraft:frozen_river");

    private static final Set<String> COLD_LAND = Set.of(
            "minecraft:snowy_plains", "minecraft:snowy_taiga", "minecraft:ice_spikes",
            "minecraft:grove", "minecraft:snowy_slopes", "minecraft:jagged_peaks",
            "minecraft:frozen_peaks", "minecraft:snowy_beach");

    private VanillaWaterBiomes() {}

    /** The inland water biome for a land biome: frozen river when the land id is one of the above. */
    public static ContentId inlandWaterFor(ContentId land) {
        return COLD_LAND.contains(land.value()) ? FROZEN_RIVER : RIVER;
    }

    /** The land ids the default mapping treats as cold, for diagnostics and tests. */
    public static Set<String> coldLandBiomes() { return COLD_LAND; }
}
