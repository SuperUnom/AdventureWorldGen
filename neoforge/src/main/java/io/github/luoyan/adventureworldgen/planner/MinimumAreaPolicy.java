package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/**
 * Minimum-area policy: which patches must survive boundary mixing, and whether the achieved dry area
 * still satisfies what the author asked for.
 *
 * <p>Both questions compare the requested minimum with the area the plan actually achieved, so they
 * are answered in one named place instead of inside the plan object and the session orchestrator.
 * The caller supplies the measurement — ownership sampling belongs to the plan queries — and the
 * reporting sink, so nothing here samples, draws or mutates a plan.
 */
public final class MinimumAreaPolicy {
    private MinimumAreaPolicy() {}

    /**
     * Patch ids whose achieved dry area is below the configured minimum: legal supply could not reach
     * the request, so boundary mixing must not shrink the achieved area any further.
     */
    public static Set<String> protectedPatchIds(AdventureWorldConfig config, List<PlannedBiomePatch> patches,
                                                ToLongFunction<PlannedBiomePatch> achievedArea) {
        Set<String> protectedIds = new LinkedHashSet<>();
        for (var demand : new RequirementExpander().expandMinimum(config).patches())
            for (var patch : patches)
                if (patch.patchId().equals(demand.patchId())
                        && achievedArea.applyAsLong(patch) < Math.min(demand.area().min(), patch.area()))
                    protectedIds.add(patch.patchId());
        return Set.copyOf(protectedIds);
    }

    /** One accepted relaxation: the plan kept the area it achieved instead of the requested minimum. */
    public record Relaxation(String patchId, long requested, long achieved, boolean noLegalArea) {}

    /**
     * Final check of the achieved dry quota.
     *
     * <p>Reports every accepted relaxation to {@code sink} as it is found, and throws as soon as
     * mixing has reduced an achieved quota below the smaller of the request and the achieved area,
     * so a plan that fails reports exactly the relaxations that preceded the failure and nothing
     * that would have come after it.
     */
    public static void checkAchievedAreas(AdventureWorldConfig config, List<PlannedBiomePatch> patches,
                                          ToLongFunction<PlannedBiomePatch> effectiveArea, Consumer<Relaxation> sink) {
        for (var demand : new RequirementExpander().expandMinimum(config).patches()) {
            var patch = patches.stream().filter(candidate -> candidate.patchId().equals(demand.patchId()))
                    .findFirst().orElse(null);
            if (patch == null) {
                sink.accept(new Relaxation(demand.patchId(), demand.area().min(), 0, true));
                continue;
            }
            long effective = effectiveArea.applyAsLong(patch);
            if (effective < Math.min(demand.area().min(), patch.area()))
                throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, FailureStage.EFFECTIVE_AREA,
                        "mixing reduced the achieved dry biome quota", Map.of("patch", patch.patchId(),
                        "effective_area", effective, "achieved_area", patch.area()));
            if (effective < demand.area().min())
                sink.accept(new Relaxation(patch.patchId(), demand.area().min(), effective, false));
        }
    }
}
