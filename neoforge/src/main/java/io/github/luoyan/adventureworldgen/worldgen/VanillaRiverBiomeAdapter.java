package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.api.BiomeAdapter;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.config.ContentId;
import io.github.luoyan.adventureworldgen.hydrology.RiverSediments;

/** Vanilla materials adapted to local river depth, with coherent sediment deposits. */
public final class VanillaRiverBiomeAdapter implements BiomeAdapter {
    private final ContentId id;
    private final RiverSediments sediments = new RiverSediments();

    public VanillaRiverBiomeAdapter(ContentId id) { this.id = id; }
    @Override public ContentId biomeId() { return id; }
    @Override public String adapterVersion() { return "vanilla-river-sediments-v2"; }
    @Override public Compatibility compatibility(MacroSample terrain) {
        boolean allowed = !terrain.hazardous() && terrain.waterKind() != WaterKind.LAVA;
        return new Compatibility(allowed, allowed ? 0.5 : 0, "vanilla river terrain");
    }
    @Override public SurfacePalette surface(MacroSample terrain) { return sediments.surface(terrain); }
    @Override public SurfacePalette surface(MacroSample terrain, long seed, int x, int z) {
        return sediments.surface(terrain, seed, x, z);
    }
}
