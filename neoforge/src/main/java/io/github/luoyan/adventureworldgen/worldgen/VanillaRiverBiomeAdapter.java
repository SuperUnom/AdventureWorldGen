package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.api.BiomeAdapter;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.plan.ContentId;

/** Planning compatibility for vanilla river biomes. */
public final class VanillaRiverBiomeAdapter implements BiomeAdapter {
    private final ContentId id;

    public VanillaRiverBiomeAdapter(ContentId id) { this.id = id; }
    @Override public ContentId biomeId() { return id; }
    // Keep the identity stable: planning compatibility has not changed.
    @Override public String adapterVersion() { return "vanilla-river-sediments-v2"; }
    @Override public Compatibility compatibility(MacroSample terrain) {
        boolean allowed = !terrain.hazardous() && terrain.waterKind() != WaterKind.LAVA;
        return new Compatibility(allowed, allowed ? 0.5 : 0, "vanilla river terrain");
    }
}
