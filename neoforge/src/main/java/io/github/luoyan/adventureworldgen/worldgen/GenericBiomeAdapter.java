package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.api.BiomeAdapter;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.plan.ContentId;

/** Conservative fallback planning policy; it deliberately makes no vegetation compatibility claim. */
public final class GenericBiomeAdapter implements BiomeAdapter {
    private static final ContentId GENERIC = new ContentId("adventureworldgen:generic");

    @Override public ContentId biomeId() { return GENERIC; }
    @Override public String adapterVersion() { return "generic-biome-v1"; }
    @Override public Compatibility compatibility(MacroSample terrain) {
        return new Compatibility(!terrain.hazardous() && terrain.waterKind() != WaterKind.LAVA, 0.0,
                terrain.hazardous() ? "hazardous terrain" : "generic ordinary-terrain fallback");
    }
}
