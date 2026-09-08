package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.api.GenericBiomeAdapter;
import io.github.luoyan.adventureworldgen.api.BiomeAdapter;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.config.ContentId;
import io.github.luoyan.adventureworldgen.api.AdapterRegistrations;

/** Built-in v1 adapter set. Other mods may build the same public registry contract in tests/integration. */
public final class MinecraftAdapters {
    private static volatile AdapterRegistry frozen;
    private MinecraftAdapters() {}

    public static AdapterRegistry builtIn() {
        AdapterRegistry current = frozen;
        if (current != null) return current;
        synchronized (MinecraftAdapters.class) {
            if (frozen == null) frozen = create();
            return frozen;
        }
    }

    private static AdapterRegistry create() {
        var builder = AdapterRegistry.builder(new GenericBiomeAdapter())
                .add(vanilla("minecraft:plains", "minecraft:grass_block", "minecraft:dirt", 3))
                .add(vanilla("minecraft:forest", "minecraft:grass_block", "minecraft:dirt", 3))
                .add(vanilla("minecraft:snowy_plains", "minecraft:grass_block", "minecraft:dirt", 3))
                .add(vanilla("minecraft:desert", "minecraft:sand", "minecraft:sandstone", 4))
                .add(vanilla("minecraft:ocean", "minecraft:sand", "minecraft:sandstone", 3))
                .add(vanilla("minecraft:river", "minecraft:gravel", "minecraft:dirt", 3))
                .add(vanilla("minecraft:frozen_river", "minecraft:gravel", "minecraft:dirt", 3))
                 .add(vanilla("minecraft:stony_peaks", "minecraft:stone", "minecraft:stone", 3))
                .add(vanilla("minecraft:jagged_peaks", "minecraft:stone", "minecraft:stone", 3))
                .add(vanilla("minecraft:frozen_peaks", "minecraft:snow_block", "minecraft:packed_ice", 3))
                .add(vanilla("minecraft:snowy_slopes", "minecraft:snow_block", "minecraft:dirt", 2))
                .add(vanilla("minecraft:windswept_gravelly_hills", "minecraft:gravel", "minecraft:stone", 2))
                .add(vanilla("minecraft:mangrove_swamp", "minecraft:mud", "minecraft:dirt", 3))
                .add(new VanillaDesertPyramidAdapter());
        var external = AdapterRegistrations.freeze();
        external.biomes().forEach(builder::add);
        external.structures().forEach(builder::add);
        return builder.build();
    }

    private static BiomeAdapter vanilla(String biome, String top, String under, int depth) {
        return new BiomeAdapter() {
            private final ContentId id = new ContentId(biome);
            private final SurfacePalette palette = new SurfacePalette(new ContentId(top), new ContentId(under),
                    new ContentId("minecraft:stone"), depth);
            @Override public ContentId biomeId() { return id; }
            @Override public String adapterVersion() { return "vanilla-surface-v1"; }
            @Override public Compatibility compatibility(MacroSample terrain) {
                boolean allowed = !terrain.hazardous() && terrain.waterKind() != WaterKind.LAVA;
                return new Compatibility(allowed, allowed ? 0.5 : 0.0, allowed ? "supported vanilla terrain" : "hazardous terrain");
            }
            @Override public SurfacePalette surface(MacroSample terrain) { return palette; }
        };
    }
}
