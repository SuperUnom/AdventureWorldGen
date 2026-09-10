package io.github.luoyan.adventureworldgen.hydrology;

import io.github.luoyan.adventureworldgen.api.BiomeAdapter.SurfacePalette;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.noise.GradientNoise;

/** Shared sediment policy for submerged river columns, including generic biome adapters. */
public final class RiverSediments {
    private static final SurfacePalette GRASS = palette("grass_block", "dirt", 3);
    private static final SurfacePalette DIRT = palette("dirt", "dirt", 3);
    private static final SurfacePalette GRAVEL = palette("gravel", "dirt", 3);
    private static final SurfacePalette SAND = palette("sand", "sandstone", 3);
    private static final SurfacePalette CLAY = palette("clay", "dirt", 2);
    private static final SurfacePalette STONE = palette("stone", "stone", 2);
    private volatile Sediments cached;

    public SurfacePalette surface(MacroSample terrain) { return terrain.wet() ? DIRT : GRASS; }

    public SurfacePalette surface(MacroSample terrain, long seed, int blockX, int blockZ) {
        if (!terrain.wet()) return GRASS;
        Sediments fields = cached;
        if (fields == null || fields.seed != seed) {
            fields = new Sediments(seed, new GradientNoise(seed, "river/sediment", 32),
                    new GradientNoise(seed, "river/clay", 13));
            cached = fields;
        }
        double deposit = fields.deposits.sample(blockX + 0.5, blockZ + 0.5);
        double patches = fields.patches.sample(blockX + 0.5, blockZ + 0.5);
        double depth = terrain.waterDepth();
        if (depth < 4.5 && patches < -0.32) return CLAY;
        if (deposit > 0.16 + StrictMath.max(0, depth - 4) * 0.045) return SAND;
        if (depth > 5.5 && patches > 0.38) return STONE;
        if (deposit > -0.16 - StrictMath.max(0, 3 - depth) * 0.10) return DIRT;
        return GRAVEL;
    }


    private static SurfacePalette palette(String top, String under, int depth) {
        return new SurfacePalette(new ContentId("minecraft:" + top), new ContentId("minecraft:" + under),
                new ContentId("minecraft:stone"), depth);
    }
    private record Sediments(long seed, GradientNoise deposits, GradientNoise patches) {}
}
