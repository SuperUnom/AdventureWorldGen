package io.github.luoyan.adventureworldgen.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Public early-registration API. Registration closes atomically before the first planning preflight. */
public final class AdapterRegistrations {
    private static final List<BiomeAdapter> BIOMES = new ArrayList<>();
    private static boolean frozen;
    private AdapterRegistrations() {}

    public static synchronized void register(BiomeAdapter adapter) {
        ensureOpen(); BIOMES.add(adapter);
    }
    public static synchronized Snapshot freeze() {
        frozen = true;
        List<BiomeAdapter> biomes = BIOMES.stream().sorted(Comparator.comparing(item -> item.biomeId().value())).toList();
        return new Snapshot(biomes);
    }

    private static void ensureOpen() {
        if (frozen) throw new IllegalStateException("AdventureWorldGen adapter registration is already frozen");
    }

    public record Snapshot(List<BiomeAdapter> biomes) {
        public Snapshot { biomes = List.copyOf(biomes); }
    }
}
