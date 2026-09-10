package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.api.BiomeAdapter;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.api.AdapterRegistrations;
import io.github.luoyan.adventureworldgen.compat.vanilla.VanillaDesertPyramidAdapter;

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
        // Ordinary biomes use the generic fallback; their placement rules come from the profile.
        var builder = AdapterRegistry.builder(new GenericBiomeAdapter())
                .add(vanilla("minecraft:ocean"))
                .add(new VanillaRiverBiomeAdapter(new ContentId("minecraft:river")))
                .add(new VanillaRiverBiomeAdapter(new ContentId("minecraft:frozen_river")))
                .add(new VanillaDesertPyramidAdapter());
        var external = AdapterRegistrations.freeze();
        external.biomes().forEach(builder::add);
        external.structures().forEach(builder::add);
        return builder.build();
    }

    private static BiomeAdapter vanilla(String biome) {
        return new BiomeAdapter() {
            private final ContentId id = new ContentId(biome);
            @Override public ContentId biomeId() { return id; }
            // Keep the identity stable: planning compatibility has not changed.
            @Override public String adapterVersion() { return "vanilla-surface-v1"; }
            @Override public Compatibility compatibility(MacroSample terrain) {
                boolean allowed = !terrain.hazardous() && terrain.waterKind() != WaterKind.LAVA;
                return new Compatibility(allowed, allowed ? 0.5 : 0.0, allowed ? "supported vanilla terrain" : "hazardous terrain");
            }
        };
    }
}
