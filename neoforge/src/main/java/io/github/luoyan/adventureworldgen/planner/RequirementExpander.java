package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.AreaRange;
import io.github.luoyan.adventureworldgen.plan.ContentId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Comparator;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.plan.StableIds;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/** Expands normalized config into stable minimum-demand objects without making spatial choices. */
public final class RequirementExpander {
    public static final int MAX_MERGED_LEVEL_SPAN = 2;

    public ExpandedRequirements expandMinimum(AdventureWorldConfig config) {return expand(config,true);}
    public ExpandedRequirements expandUnmerged(AdventureWorldConfig config) {return expand(config,false);}
    /** Recover quota roles from stable source IDs after a spatially incompatible group was split. */
    public List<PatchDemand> demandsForLayout(AdventureWorldConfig config,List<io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch> patches) {
        var ids=patches.stream().map(io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch::patchId).collect(java.util.stream.Collectors.toSet());
        var sources=expandUnmerged(config).patches();var result=new ArrayList<PatchDemand>();
        for(var group:expandMinimum(config).patches()) {
            boolean split=group.members().stream().anyMatch(m->!m.patchId().equals(group.patchId())&&ids.contains(m.patchId()));
            if(split)for(var source:sources) {
                if(group.members().stream().anyMatch(m->m.patchId().equals(source.patchId())))result.add(source);
            } else result.add(group);
        }
        return List.copyOf(result);
    }
    private ExpandedRequirements expand(AdventureWorldConfig config,boolean mergeSources) {
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
        // Exactly one source owns the spawn role, including when that source joins a group.
        for (int i = 0; i < patches.size(); i++) {
            var patch = patches.get(i);
            if (patch.adventureLevel() == 0 && patch.allowedBiomes().contains(config.spawn().biome())) {
                patches.set(i, patch.withRoles(true, List.of()));
                break;
            }
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
                    instance.carrierArea(),instance.instanceId(),true).withRoles(false,List.of(instance.instanceId())));
        }
        // Grouping uses level order, but diagnostics retain source order when nothing merges.
        Map<String, Integer> sourceOrder = new java.util.HashMap<>();
        for (int i = 0; i < patches.size(); i++) sourceOrder.put(patches.get(i).patchId(), i);
        var merged = new ArrayList<>(mergeSources?merge(patches):patches);
        merged.sort(Comparator.comparingInt(group -> group.members().stream()
                .mapToInt(member -> sourceOrder.get(member.patchId())).min().orElseThrow()));
        return new ExpandedRequirements(merged, instances);
    }

    /** Stable, non-transitive grouping; every member must share one allowed biome. */
    static List<PatchDemand> merge(List<PatchDemand> sources) {
        var ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.comparingInt(PatchDemand::adventureLevel).thenComparing(PatchDemand::patchId));
        var groups = new ArrayList<PatchDemand>();
        for (var source : ordered) {
            var match = groups.stream().filter(group -> group.canMerge(source))
                    .min(Comparator.comparing((PatchDemand group) -> group.implicit())
                            .thenComparingInt(group -> Math.abs(group.adventureLevel() - source.adventureLevel()))
                            .thenComparing(PatchDemand::patchId)).orElse(null);
            if (match == null) groups.add(source);
            else groups.set(groups.indexOf(match), match.mergeWith(source));
        }
        return List.copyOf(groups);
    }

    public record ExpandedRequirements(List<PatchDemand> patches, List<StructureDemand> structures) {
        public ExpandedRequirements {
            patches = List.copyOf(patches);
            structures = List.copyOf(structures);
        }

        public String carrierPatchId(String instanceId) {
            return patches.stream().filter(patch -> patch.structureInstances().contains(instanceId))
                    .map(PatchDemand::patchId).findFirst().orElseThrow();
        }
    }

    public record Member(String patchId, String sourceId, int adventureLevel) {}

    public record PatchDemand(String patchId, List<ContentId> allowedBiomes, int adventureLevel,
                              AreaRange area, String sourceId, boolean implicit,
                              List<Member> members, boolean spawn, List<String> structureInstances) {
        public PatchDemand(String patchId, List<ContentId> allowedBiomes, int adventureLevel,
                           AreaRange area, String sourceId, boolean implicit) {
            this(patchId, allowedBiomes, adventureLevel, area, sourceId, implicit,
                    List.of(new Member(patchId, sourceId, adventureLevel)), false, List.of());
        }

        public PatchDemand {
            allowedBiomes = List.copyOf(allowedBiomes);
            members = List.copyOf(members);
            structureInstances = List.copyOf(structureInstances);
        }

        PatchDemand withRoles(boolean spawn, List<String> instances) {
            return new PatchDemand(patchId, allowedBiomes, adventureLevel, area, sourceId, implicit,
                    members, spawn, instances);
        }

        public int minimumLevel() { return members.stream().mapToInt(Member::adventureLevel).min().orElseThrow(); }
        public int maximumLevel() { return members.stream().mapToInt(Member::adventureLevel).max().orElseThrow(); }
        public boolean requiresSeed() { return spawn || !structureInstances.isEmpty(); }

        boolean canMerge(PatchDemand other) {
            return Math.max(maximumLevel(), other.maximumLevel()) - Math.min(minimumLevel(), other.minimumLevel())
                    <= MAX_MERGED_LEVEL_SPAN && allowedBiomes.stream().anyMatch(other.allowedBiomes::contains);
        }

        PatchDemand mergeWith(PatchDemand other) {
            if (!canMerge(other)) throw new IllegalArgumentException("incompatible biome demand group");
            var joined = new ArrayList<>(members);
            joined.addAll(other.members);
            joined.sort(Comparator.comparing(Member::patchId));
            var instances = new ArrayList<>(structureInstances);
            instances.addAll(other.structureInstances);
            instances.sort(String::compareTo);
            boolean hasSpawn = spawn || other.spawn;
            int level = hasSpawn ? 0 : (int) Math.round(joined.stream().mapToInt(Member::adventureLevel).average().orElseThrow());
            // Preserve a real source identity; no ID prefix is used to infer the merged role.
            var identity = spawn ? this : other.spawn ? other : !implicit ? this : !other.implicit ? other : this;
            return new PatchDemand(identity.patchId, allowedBiomes.stream().filter(other.allowedBiomes::contains).sorted().toList(),
                    level, addAreas(area, other.area), identity.sourceId, implicit && other.implicit,
                    joined, hasSpawn, instances);
        }
    }

    private static AreaRange addAreas(AreaRange first, AreaRange second) {
        try {
            long max = first.max() == Long.MAX_VALUE || second.max() == Long.MAX_VALUE
                    ? Long.MAX_VALUE : Math.addExact(first.max(), second.max());
            return new AreaRange(Math.addExact(first.min(), second.min()), max,
                    Math.addExact(first.target(), second.target()));
        } catch (ArithmeticException overflow) {
            throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, FailureStage.REQUIREMENTS,
                    "merged biome area exceeds the supported range", Map.of("first", first, "second", second));
        }
    }

    /** One expanded author-requested structure instance; it contains no structure-intrinsic facts. */
    public record StructureDemand(String instanceId, ContentId structureId, int sequence,
                                  int adventureLevel, List<ContentId> allowedBiomes, AreaRange carrierArea,
                                  AdventureWorldConfig.Spacing spacing, boolean required) {
        public StructureDemand { allowedBiomes = List.copyOf(allowedBiomes); }
    }
}
