package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.config.LoadedProfile;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyGenerator;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyProfile;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.terrain.CoastGenerator;
import io.github.luoyan.adventureworldgen.persistence.AtomicPlanRepository;
import io.github.luoyan.adventureworldgen.persistence.PlanV2Codec;
import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.planner.JointPlanner;
import io.github.luoyan.adventureworldgen.plan.PlanningStage;
import io.github.luoyan.adventureworldgen.cost.CostPlanner;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.erosion.ErosionGenerator;

import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deterministic stage orchestration; wall-clock time is logging only and never a termination input. */
public final class RuntimePlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimePlanner.class);
    private RuntimePlanner() {}

    public static GeneratedAdventurePlan plan(long seed, LoadedProfile loaded, Path worldDirectory,
                                              AdapterRegistry adapters) {
        var progress = PlanningProgress.begin(loaded.id().toString());
        try {
            var result = plan(seed, loaded, worldDirectory, adapters, progress);
            progress.complete();
            return result;
        } catch (RuntimeException | Error failure) {
            progress.fail();
            throw failure;
        }
    }

    private static GeneratedAdventurePlan plan(long seed, LoadedProfile loaded, Path worldDirectory,
                                               AdapterRegistry adapters, PlanningProgress.Run progress) {
        String inputHash = PlanIdentity.hash(seed, loaded, adapters);
        AtomicPlanRepository repository = new AtomicPlanRepository();
        PlanV2Codec codec = new PlanV2Codec();
        try {
            var existing = repository.loadReady(worldDirectory, loaded.id(), inputHash);
            if (existing.isPresent()) {
                LOGGER.info("AdventureWorldGen loading READY {} for {}", PlannerProfile.V2.planFormatVersion(), loaded.id());
                return GeneratedAdventurePlan.restore(loaded.config(),
                        codec.decode(existing.get().canonicalPlan(), loaded.id(), inputHash));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("could not load AdventureWorldGen plan", failure);
        }
        long started = System.nanoTime();
        var metrics=new PlanningMetrics();
        double radius = loaded.config().world().radius();
        double spawnRadius = StrictMath.min(256.0, radius / 10.0);
        double spawnFootprint = StructureAdapterBridge.spawnReservationRadius(adapters, loaded.config());
        double keep = spawnRadius + spawnFootprint + StrictMath.min(32.0, radius / 20.0);
        LOGGER.info("AdventureWorldGen planning coast for {} with seed {}", loaded.id(), seed);
        progress.stage(PlanningStage.COAST);
        var coast = new CoastGenerator(PlannerProfile.V2).generate(seed, radius, keep);
        metrics.finish(PlanningMetrics.Stage.COAST);
        LOGGER.info("AdventureWorldGen planning continuous regions for {}", loaded.id());
        var capacities = io.github.luoyan.adventureworldgen.planner.TerrainCapacitySolver.reserve(seed,loaded.config(),coast.coastline(),coast.landBand());
        metrics.finish(PlanningMetrics.Stage.CAPACITY_RESERVATION);
        var terrainFoundation = PlanTerrain.foundation(seed, loaded.config(), capacities, coast.coastline(),
                64.0, coast.landBand(), coast.seaBand(), "terrain-r22");
        LOGGER.info("AdventureWorldGen simulating and freezing erosion delta field for {}", loaded.id());
        progress.stage(PlanningStage.EROSION);
        int erosionSpacing = 8;
        int erosionExtent = (int) StrictMath.ceil((radius + 256.0) / erosionSpacing) * erosionSpacing;
        int erosionSize = erosionExtent * 2 / erosionSpacing + 1;
        var erosion = new ErosionGenerator(PlannerProfile.V2, HydrologyProfile.FINITE_CONTINENT)
                .generate(seed, terrainFoundation.island(), -erosionExtent, -erosionExtent, erosionSpacing, erosionSize, erosionSize, progress.within(PlanningStage.EROSION));
        metrics.finish(PlanningMetrics.Stage.EROSION);
        var erodedIsland = terrainFoundation.eroded(erosion);
        LOGGER.info("AdventureWorldGen planning {} hydrology for {}", PlannerProfile.V2.hydrologyVersion(), loaded.id());
        progress.stage(PlanningStage.RIVERS);
        var rivers = new HydrologyGenerator(PlannerProfile.V2, HydrologyProfile.FINITE_CONTINENT)
                .generate(seed, radius, 64.0, coast.coastline(), erodedIsland);
        metrics.finish(PlanningMetrics.Stage.RIVERS);
        // Planning queries keep the memoizing wrapper warm and the same stack is handed to the
        // plan; a READY reload composes this stack without the wrapper (accepted optimization).
        var planningTerrain = PlanTerrain.compose(terrainFoundation, erodedIsland, rivers).withMemoizedQueries();
        var erodedTerrain = planningTerrain.terrain();
        LOGGER.info("AdventureWorldGen building complete 16-block directed cost graph for {}", loaded.id());
        progress.stage(PlanningStage.COSTS);
        var costs = new CostPlanner(PlannerProfile.V2).build(erodedTerrain, coast.coastline(), new Vec2(0.5, 0.5), progress.within(PlanningStage.COSTS));
        metrics.finish(PlanningMetrics.Stage.COST_GRAPH);
        LOGGER.info("AdventureWorldGen cost graph has {} nodes and {} canonical edges for {}",
                costs.nodeCount(), costs.edgeStats().computations(), loaded.id());
        LOGGER.info("AdventureWorldGen jointly planning biome patches and structures for {}", loaded.id());
        progress.stage(PlanningStage.PLACEMENT);
        var jointPlanner = new JointPlanner(PlannerProfile.V2);
        var joint = jointPlanner.plan(seed, loaded.config(), erodedTerrain,
                new StructureAdapterBridge(adapters, erodedTerrain),
                new JointPlanner.LevelConstraint() {
                    public boolean accepts(int level,int x,int z) { return true; }
                    public double penalty(int level,int x,int z) {
                        return io.github.luoyan.adventureworldgen.cost.AdventurePreference.penalty(level,
                                costs.normalizedPreferenceAt(x,z,loaded.config().world().radius()));
                    }
                }, (biome, x, z) -> adapters.biome(biome)
                        .compatibility(erodedTerrain.sample(x + 0.5, z + 0.5)).allowed(), progress, progress.within(PlanningStage.PLACEMENT), metrics::finish);
        GeneratedAdventurePlan plan = GeneratedAdventurePlan.fromPlanning(seed, loaded.config(), coast.coastline(), rivers,
                64.0, coast.landBand(), coast.seaBand(), "terrain-r22", joint.spawn(),
                joint.patches(), joint.structures(), new io.github.luoyan.adventureworldgen.plan.PlanDiagnostics(
                coast.vertexCount(), rivers.channels().size(),
                rivers.channels().stream().mapToLong(channel -> channel.points().size()).sum(),
                (long) erosion.width() * erosion.height(), erosion.operationCount(),
                costs.nodeCount(), costs.edgeStats().computations(), joint.operationCount(),
                "terrain-r22+" + PlannerProfile.V2.hydrologyVersion() + "+erosion-v2"), erosion,capacities,
                new GeneratedAdventurePlan.PlanningInputs(planningTerrain,jointPlanner.climate()), progress);
        metrics.finish(PlanningMetrics.Stage.FILLER_AND_TRANSITION);
        progress.stage(PlanningStage.VALIDATION);
        // The policy compares request against achieved area; reporting and failure text stay here.
        io.github.luoyan.adventureworldgen.planner.MinimumAreaPolicy.checkAchievedAreas(loaded.config(),
                plan.biomePatches(), plan::effectiveArea, relaxation -> {
                    if (relaxation.noLegalArea())
                        LOGGER.warn("Biome minimum relaxed: {} has no legal area; requested={}",
                                relaxation.patchId(), relaxation.requested());
                    else LOGGER.warn("Biome minimum relaxed: {} requested={}, effective={}",
                            relaxation.patchId(), relaxation.requested(), relaxation.achieved());
                });
        metrics.finish(PlanningMetrics.Stage.VALIDATION);
        metrics.adventure(joint.patches(),costs,radius);
        progress.stage(PlanningStage.SAVE);
        try {
            repository.publishAtomically(worldDirectory, loaded.id(), codec.encode(loaded.id(), inputHash, plan.snapshot()), inputHash);
        } catch (IOException failure) {
            throw new IllegalStateException("could not atomically publish AdventureWorldGen plan", failure);
        }
        LOGGER.info("AdventureWorldGen plan READY for {} in {} ms", loaded.id(), (System.nanoTime() - started) / 1_000_000);
        metrics.finish(PlanningMetrics.Stage.SAVE);
        try { metrics.write(worldDirectory,seed,plan); }
        catch(IOException unavailable) { LOGGER.warn("Could not write planning timing diagnostics",unavailable); }
        return plan;
    }
}
