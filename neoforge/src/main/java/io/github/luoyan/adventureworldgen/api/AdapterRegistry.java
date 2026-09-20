package io.github.luoyan.adventureworldgen.api;

import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/** Deterministically ordered adapter registrations, frozen before planning starts. */
public final class AdapterRegistry {
    private final Map<ContentId, BiomeAdapter> biomes;
    private final BiomeAdapter genericBiome;

    private AdapterRegistry(Map<ContentId, BiomeAdapter> biomes, BiomeAdapter genericBiome) {
        this.biomes = Collections.unmodifiableMap(new TreeMap<>(biomes));
        this.genericBiome = genericBiome;
    }

    public BiomeAdapter biome(ContentId id) {
        return biomes.getOrDefault(id, genericBiome);
    }

    public Collection<String> versionKeys() {
        ArrayList<String> result = new ArrayList<>();
        biomes.forEach((id, adapter) -> result.add("biome/" + id + "=" + adapter.adapterVersion()));
        result.add("biome/*=" + genericBiome.adapterVersion());
        return ListCopy.sorted(result);
    }

    public static Builder builder(BiomeAdapter genericBiome) { return new Builder(genericBiome); }

    public static final class Builder {
        private final TreeMap<ContentId, BiomeAdapter> biomes = new TreeMap<>();
        private final BiomeAdapter genericBiome;

        private Builder(BiomeAdapter genericBiome) { this.genericBiome = genericBiome; }

        public Builder add(BiomeAdapter adapter) {
            if (biomes.putIfAbsent(adapter.biomeId(), adapter) != null)
                duplicate("biome", adapter.biomeId());
            return this;
        }

        public AdapterRegistry build() { return new AdapterRegistry(biomes, genericBiome); }

        private static void duplicate(String kind, ContentId id) {
            throw new PlanningFailure(PlanningFailure.Code.CONFIG_CONFLICT, FailureStage.ADAPTER_REGISTRATION,
                    "duplicate " + kind + " adapter", Map.of("content_id", id));
        }
    }

    private static final class ListCopy {
        static Collection<String> sorted(ArrayList<String> input) {
            input.sort(String::compareTo);
            return List.copyOf(input);
        }
    }
}
