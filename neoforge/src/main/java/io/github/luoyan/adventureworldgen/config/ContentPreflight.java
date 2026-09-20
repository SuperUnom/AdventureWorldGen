package io.github.luoyan.adventureworldgen.config;

import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;

import java.util.Map;
import java.util.TreeSet;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/**
 * Resolves every referenced registry object and required adapter before any adventure chunk can
 * generate.
 *
 * <p>{@link #validate} is a check, not a factory: it succeeds by returning and fails by throwing
 * {@link PlanningFailure}. No caller consumes a resolved-content value, so the check publishes
 * none - a second content state kept beside the config, the registry and the adapter registry
 * could only drift out of step with them.
 */
public final class ContentPreflight {
    private static final ContentId OCEAN = new ContentId("minecraft:ocean");

    public void validate(AdventureWorldConfig config, RegistryLookup registries,
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
        }
    }

    private static void missing(String kind, ContentId id) {
        throw new PlanningFailure(PlanningFailure.Code.UNSUPPORTED_CONTENT, FailureStage.CONTENT_PREFLIGHT,
                "configured " + kind + " is absent from the active registry", Map.of("content_id", id));
    }

    private static void unsupported(String kind, ContentId id, String reason) {
        throw new PlanningFailure(PlanningFailure.Code.UNSUPPORTED_CONTENT, FailureStage.CONTENT_PREFLIGHT,
                reason, Map.of("content_kind", kind, "content_id", id));
    }

    public interface RegistryLookup {
        boolean biomeExists(ContentId id);
        boolean structureExists(ContentId id);
        /** Null only for registry-independent tooling; Minecraft supplies the real biome climate. */
        default Boolean snowyAtSeaLevel(ContentId id){return null;}
    }
}
