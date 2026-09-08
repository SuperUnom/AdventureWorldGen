package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.AreaRange;
import io.github.luoyan.adventureworldgen.config.ContentId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Expands normalized config into stable minimum-demand objects without making spatial choices. */
public final class RequirementExpander {
    public ExpandedRequirements expandMinimum(AdventureWorldConfig config) {
        Objects.requireNonNull(config, "config");
        List<PatchDemand> patches = new ArrayList<>();
        for (var required : config.biomes().required()) {
            patches.add(new PatchDemand(required.patchId(), List.of(required.id()), required.adventureLevel(),
                    required.area(), required.requestId(), false));
        }
        if (config.spawn().hasBiome()) {
            boolean represented = config.biomes().required().stream().anyMatch(required ->
                    required.adventureLevel() == 0 && required.id().equals(config.spawn().biome()));
            if (!represented) {
                patches.add(new PatchDemand(StableIds.implicitSpawnPatch(), List.of(config.spawn().biome()), 0,
                        AreaRange.DEFAULT, "spawn.biome", true));
            }
        }

        List<StructureInstanceDemand> instances = new ArrayList<>();
        for (var structure : config.structures()) {
            boolean spawn = config.spawn().hasStructure() && config.spawn().structure().id().equals(structure.id());
            long minimum = structure.effectiveMinimum(spawn);
            if (minimum > Integer.MAX_VALUE) {
                throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, "requirements",
                        "structure minimum cannot be represented by planner-v2",
                        Map.of("structure", structure.id(), "minimum", minimum));
            }
            for (int sequence = 0; sequence < minimum; sequence++) {
                instances.add(new StructureInstanceDemand(StableIds.structureInstance(structure.id(), sequence),
                        structure.id(), sequence, structure.adventureLevel(), structure.allowedBiomes().ids(),
                        structure.allowedBiomes().area(), structure.entrance(), spawn && sequence == 0, true));
            }
        }
        return new ExpandedRequirements(patches, instances);
    }

    public record ExpandedRequirements(List<PatchDemand> patches, List<StructureInstanceDemand> structures) {
        public ExpandedRequirements {
            patches = List.copyOf(patches);
            structures = List.copyOf(structures);
        }
    }

    public record PatchDemand(String patchId, List<ContentId> allowedBiomes, int adventureLevel,
                              AreaRange area, String sourceId, boolean implicit) {
        public PatchDemand { allowedBiomes = List.copyOf(allowedBiomes); }
    }

    public record StructureInstanceDemand(String instanceId, ContentId structureId, int sequence,
                                          int adventureLevel, List<ContentId> allowedBiomes, AreaRange carrierArea,
                                          AdventureWorldConfig.Vec3d relativeEntrance,
                                          boolean spawnInstance, boolean required) {
        public StructureInstanceDemand { allowedBiomes = List.copyOf(allowedBiomes); }
    }
}
