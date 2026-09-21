package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.plan.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.IOException;

/** Loads pure, explicit road envelopes without inspecting templates or constructing pieces. */
public final class StructureRoadInformation {
    private StructureRoadInformation() {}
    public static StructurePlanningCatalog load(ResourceManager resources, AdventureWorldConfig config) throws IOException {
        return load(config, id -> {
            var location=ResourceLocation.parse(id.value()).withPath("adventureworldgen/structure_planning/"+id.value().split(":")[1]+".json");
            var resource=resources.getResource(location);
            return resource.isPresent()?resource.get().openAsReader():null;
        });
    }
    public static StructurePlanningCatalog load(AdventureWorldConfig config,io.github.luoyan.adventureworldgen.config.StructurePlanningJson.ResourceOpener resources) throws IOException {
        return io.github.luoyan.adventureworldgen.config.StructurePlanningJson.load(config,resources);
    }
}
