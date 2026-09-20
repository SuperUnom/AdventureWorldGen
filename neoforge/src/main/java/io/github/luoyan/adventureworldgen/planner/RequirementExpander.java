package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.AreaRange;
import io.github.luoyan.adventureworldgen.plan.ContentId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.plan.StableIds;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/** Expands normalized config into stable minimum-demand objects without making spatial choices. */
public final class RequirementExpander {
    public ExpandedRequirements expandMinimum(AdventureWorldConfig config) {
        Objects.requireNonNull(config, "config");
        List<PatchDemand> patches = new ArrayList<>();
        for (var required : config.biomes().required()) {
            patches.add(new PatchDemand(required.patchId(), List.of(required.id()), required.adventureLevel(),
                    required.area(), required.requestId(), false));
        }
        boolean represented = config.biomes().required().stream().anyMatch(required ->
                required.adventureLevel() == 0 && required.id().equals(config.spawn().biome()));
        if (!represented) {
            patches.add(new PatchDemand(StableIds.implicitSpawnPatch(), List.of(config.spawn().biome()), 0,
                    AreaRange.DEFAULT, "spawn.biome", true));
        }

        List<StructureDemand> instances = new ArrayList<>();
        for (var structure : config.structures()) {
            long minimum = structure.effectiveMinimum();
            if (minimum > Integer.MAX_VALUE) {
                throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, FailureStage.REQUIREMENTS,
                        "structure minimum cannot be represented by the planner",
                        Map.of("structure", structure.id(), "minimum", minimum));
            }
            for (int sequence = 0; sequence < minimum; sequence++) {
                instances.add(new StructureDemand(StableIds.structureInstance(structure.id(), sequence),
                        structure.id(), sequence, structure.adventureLevel(), structure.allowedBiomes().ids(),
                        structure.allowedBiomes().area(), structure.spacing(), true));
            }
        }
        for(var instance:instances) {
            var allowed=instance.allowedBiomes().isEmpty()?config.biomes().filler():instance.allowedBiomes();
            patches.add(new PatchDemand(StableIds.carrierPatch(instance.instanceId()),allowed,instance.adventureLevel(),
                    instance.carrierArea(),instance.instanceId(),true));
        }
        return new ExpandedRequirements(patches, instances);
    }

    public record ExpandedRequirements(List<PatchDemand> patches, List<StructureDemand> structures) {
        public ExpandedRequirements {
            patches = List.copyOf(patches);
            structures = List.copyOf(structures);
        }
    }

    public record PatchDemand(String patchId, List<ContentId> allowedBiomes, int adventureLevel,
                              AreaRange area, String sourceId, boolean implicit) {
        public PatchDemand { allowedBiomes = List.copyOf(allowedBiomes); }
    }

    /** One expanded author-requested structure instance; it contains no structure-intrinsic facts. */
    public record StructureDemand(String instanceId, ContentId structureId, int sequence,
                                  int adventureLevel, List<ContentId> allowedBiomes, AreaRange carrierArea,
                                  AdventureWorldConfig.Spacing spacing, boolean required) {
        public StructureDemand { allowedBiomes = List.copyOf(allowedBiomes); }
    }
}
