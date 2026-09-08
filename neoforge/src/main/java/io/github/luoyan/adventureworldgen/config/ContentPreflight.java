package io.github.luoyan.adventureworldgen.config;

import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.planner.PlanningFailure;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Resolves every referenced registry object and required adapter before any adventure chunk can generate. */
public final class ContentPreflight {
    private static final ContentId OCEAN = new ContentId("minecraft:ocean");

    public ResolvedContent validate(AdventureWorldConfig config, RegistryLookup registries,
                                    AdapterRegistry adapters) {
        TreeSet<ContentId> biomeIds = new TreeSet<>();
        biomeIds.add(OCEAN);
        biomeIds.add(new ContentId("minecraft:river"));
        biomeIds.add(new ContentId("minecraft:frozen_river"));
        biomeIds.addAll(config.biomes().filler());
        biomeIds.addAll(config.biomes().terrainRules().keySet());
        config.biomes().required().forEach(required -> biomeIds.add(required.id()));
        if (config.spawn().biome() != null) biomeIds.add(config.spawn().biome());
        for (var structure : config.structures()) biomeIds.addAll(structure.allowedBiomes().ids());

        for (ContentId id : biomeIds) {
            if (!registries.biomeExists(id)) missing("biome", id);
            if (adapters.biome(id) == null) unsupported("biome", id, "no biome adapter or generic fallback");
        }

        TreeSet<ContentId> structureIds = new TreeSet<>();
        for (var structure : config.structures()) structureIds.add(structure.id());
        for (ContentId id : structureIds) {
            if (!registries.structureExists(id)) missing("structure", id);
            var adapter = adapters.structure(id).orElse(null);
            if (adapter == null) unsupported("structure", id, "no structure adapter");
            if (!adapter.describe().canFreezeAllPieces())
                unsupported("structure", id, "adapter cannot freeze all structure pieces");
        }
        return new ResolvedContent(List.copyOf(biomeIds), List.copyOf(structureIds),
                List.copyOf(adapters.versionKeys()));
    }

    private static void missing(String kind, ContentId id) {
        throw new PlanningFailure(PlanningFailure.Code.UNSUPPORTED_CONTENT, "content-preflight",
                "configured " + kind + " is absent from the active registry", Map.of("content_id", id));
    }

    private static void unsupported(String kind, ContentId id, String reason) {
        throw new PlanningFailure(PlanningFailure.Code.UNSUPPORTED_CONTENT, "content-preflight",
                reason, Map.of("content_kind", kind, "content_id", id));
    }

    public interface RegistryLookup {
        boolean biomeExists(ContentId id);
        boolean structureExists(ContentId id);
    }

    public record ResolvedContent(List<ContentId> biomeIds, List<ContentId> structureIds,
                                  List<String> adapterVersions) {
        public ResolvedContent {
            biomeIds = List.copyOf(biomeIds);
            structureIds = List.copyOf(structureIds);
            adapterVersions = List.copyOf(adapterVersions);
        }
    }
}
