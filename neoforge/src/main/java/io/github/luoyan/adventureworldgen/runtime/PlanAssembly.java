package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.plan.BiomeLayout;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.plan.PlanningObserver;
import io.github.luoyan.adventureworldgen.plan.PlanningStage;
import io.github.luoyan.adventureworldgen.planner.BiomeEnvironmentRules;
import io.github.luoyan.adventureworldgen.planner.ClimatePlan;
import io.github.luoyan.adventureworldgen.planner.FillerLayout;

import java.util.List;

/**
 * The assembly steps that turn frozen-or-fresh inputs into the objects a plan queries.
 *
 * <p>Two entries use them. First planning has no frozen layout and may hand over the climate field
 * it already built during joint placement, so the plan reuses that object instead of computing the
 * same field twice. A READY reload has a frozen layout and rebuilds the field from it without
 * emitting planning stages. Both go through here so the "frozen or fresh" decision, the stage
 * notifications and the reuse rule live in one place instead of inside the plan object.
 *
 * <p>What stays in the plan is what needs the plan's own queries: the spawn position when no frozen
 * one exists, and the minimum-area protection that measures the layout being assembled.
 */
final class PlanAssembly {
    private PlanAssembly() {}

    /** The biome layout half of a plan: the climate field, the rules reading it, the filler grid. */
    record Layout(ClimatePlan climate, BiomeEnvironmentRules rules, FillerLayout filler) {}

    /**
     * @param frozenLayout the frozen layout of a READY plan, or null when this is a first planning run
     * @param reuseClimate the climate field joint placement already built, or null when it must be
     *                     built here (a READY reload rebuilds it from {@code frozenLayout})
     */
    static Layout layout(long seed, AdventureWorldConfig config, MacroTerrain terrain,
                         List<PlannedBiomePatch> patches, BiomeLayout frozenLayout, ClimatePlan reuseClimate,
                         PlanningObserver observer) {
        ClimatePlan climate = reuseClimate != null ? reuseClimate
                : new ClimatePlan(seed, config, terrain, ignored -> {}, observer,
                        frozenLayout == null ? null : frozenLayout.climate());
        if (frozenLayout == null) observer.stage(PlanningStage.FILLER);
        BiomeEnvironmentRules rules = new BiomeEnvironmentRules(config, climate);
        FillerLayout filler = new FillerLayout(seed, config, terrain, patches, rules, observer,
                frozenLayout == null ? null : frozenLayout.filler());
        if (frozenLayout == null) observer.stage(PlanningStage.TRANSITION);
        return new Layout(climate, rules, filler);
    }

    /** The frozen spawn, or the origin column raised one block above its ground surface. */
    static AdventurePlanView.SpawnPosition spawn(MacroTerrain terrain, AdventurePlanView.SpawnPosition frozenSpawn) {
        if (frozenSpawn != null) return frozenSpawn;
        double y = terrain.sample(0, 0).groundSurface() + 1.0;
        return new AdventurePlanView.SpawnPosition(0.5, StrictMath.ceil(y), 0.5, 0);
    }
}
