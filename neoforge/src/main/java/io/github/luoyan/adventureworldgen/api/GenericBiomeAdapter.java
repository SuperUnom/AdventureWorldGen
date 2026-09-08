package io.github.luoyan.adventureworldgen.api;

import io.github.luoyan.adventureworldgen.config.ContentId;

/** Conservative fallback surface policy; it deliberately makes no vegetation compatibility claim. */
public final class GenericBiomeAdapter implements BiomeAdapter {
    private static final ContentId GENERIC = new ContentId("adventureworldgen:generic");
    private static final ContentId GRASS = new ContentId("minecraft:grass_block");
    private static final ContentId DIRT = new ContentId("minecraft:dirt");
    private static final ContentId STONE = new ContentId("minecraft:stone");

    @Override public ContentId biomeId() { return GENERIC; }
    @Override public String adapterVersion() { return "generic-biome-v1"; }
    @Override public Compatibility compatibility(MacroSample terrain) {
        return new Compatibility(!terrain.hazardous() && terrain.waterKind() != WaterKind.LAVA, 0.0,
                terrain.hazardous() ? "hazardous terrain" : "generic ordinary-terrain fallback");
    }
    @Override public SurfacePalette surface(MacroSample terrain) {
        return new SurfacePalette(GRASS, DIRT, STONE, 3);
    }
}
