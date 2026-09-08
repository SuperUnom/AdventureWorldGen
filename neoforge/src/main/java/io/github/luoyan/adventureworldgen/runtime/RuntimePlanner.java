package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.config.ProfileManager;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyGenerator;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyProfile;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyTerrain;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import io.github.luoyan.adventureworldgen.terrain.CoastGenerator;
import io.github.luoyan.adventureworldgen.terrain.IslandMacroTerrain;
import io.github.luoyan.adventureworldgen.terrain.RegionTerrain;
import io.github.luoyan.adventureworldgen.persistence.AtomicPlanRepository;
import io.github.luoyan.adventureworldgen.persistence.PlanV2Codec;
import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.planner.JointPlanner;
import io.github.luoyan.adventureworldgen.planner.PlanningFailure;
import io.github.luoyan.adventureworldgen.cost.CostPlanner;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.erosion.ErosionGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deterministic stage orchestration; wall-clock time is logging only and never a termination input. */
public final class RuntimePlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimePlanner.class);
    /** Internal cache key revision; public data contracts deliberately remain planner-v2 / plan-v2. */
    public static final String IMPLEMENTATION_REVISION = "planner-v2-impl-2026-09-08-r22-ftf-continuous-relief-v2";
    private RuntimePlanner() {}

    public static GeneratedAdventurePlan plan(long seed, ProfileManager.LoadedProfile loaded, Path worldDirectory,
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

    private static GeneratedAdventurePlan plan(long seed, ProfileManager.LoadedProfile loaded, Path worldDirectory,
                                               AdapterRegistry adapters, PlanningProgress.Run progress) {
        String inputHash = inputHash(seed, loaded, adapters);
        AtomicPlanRepository repository = new AtomicPlanRepository();
        PlanV2Codec codec = new PlanV2Codec();
        try {
            var existing = repository.loadReady(worldDirectory, loaded.id(), inputHash);
            if (existing.isPresent()) {
                LOGGER.info("AdventureWorldGen loading READY {} for {}", PlannerProfile.V2.planFormatVersion(), loaded.id());
                return codec.decode(existing.get().canonicalPlan(), loaded.id(), inputHash, loaded.config());
            }
        } catch (IOException failure) {
            throw new IllegalStateException("could not load AdventureWorldGen plan", failure);
        }
        long started = System.nanoTime();
        var metrics=new PlanningMetrics();
        double radius = loaded.config().world().radius();
        double spawnRadius = StrictMath.min(256.0, radius / 10.0);
        double spawnFootprint = loaded.config().spawn().hasStructure()
                ? adapters.structure(loaded.config().spawn().structure().id()).orElseThrow(() ->
                new PlanningFailure(PlanningFailure.Code.UNSUPPORTED_CONTENT, "spawn-reservation",
                        "spawn structure has no adapter", java.util.Map.of("content_id",
                        loaded.config().spawn().structure().id()))).describe().maximumFootprintRadius()
                + StrictMath.hypot(loaded.config().spawn().structure().spawnPoint().x(),
                loaded.config().spawn().structure().spawnPoint().z()) : 0.0;
        double keep = spawnRadius + spawnFootprint + StrictMath.min(32.0, radius / 20.0);
        LOGGER.info("AdventureWorldGen planning coast for {} with seed {}", loaded.id(), seed);
        progress.stage(PlanningProgress.Stage.COAST);
        var coast = new CoastGenerator(PlannerProfile.V2).generate(seed, radius, keep);
        metrics.finish("coast");
        LOGGER.info("AdventureWorldGen planning continuous regions for {}", loaded.id());
        var capacities = io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan.reserve(seed,loaded.config(),coast.coastline(),coast.landBand());
        metrics.finish("capacity_reservation");
        var regions = new RegionTerrain(seed, PlannerProfile.V2,capacities,loaded.config().world().terrain(),loaded.config());
        var island = new IslandMacroTerrain(coast.coastline(), regions, seed, 64.0,
                coast.landBand(), coast.seaBand(), "terrain-r22");
        LOGGER.info("AdventureWorldGen simulating and freezing erosion delta field for {}", loaded.id());
        progress.stage(PlanningProgress.Stage.EROSION);
        int erosionSpacing = 8;
        int erosionExtent = (int) StrictMath.ceil((radius + 256.0) / erosionSpacing) * erosionSpacing;
        int erosionSize = erosionExtent * 2 / erosionSpacing + 1;
        var erosion = new ErosionGenerator(PlannerProfile.V2, HydrologyProfile.FINITE_CONTINENT)
                .generate(seed, island, -erosionExtent, -erosionExtent, erosionSpacing, erosionSize, erosionSize, progress.within(PlanningProgress.Stage.EROSION));
        metrics.finish("erosion");
        var erodedIsland = new io.github.luoyan.adventureworldgen.erosion.ErodedTerrain(island, erosion, "erosion-v2");
        LOGGER.info("AdventureWorldGen planning {} hydrology for {}", PlannerProfile.V2.hydrologyVersion(), loaded.id());
        progress.stage(PlanningProgress.Stage.RIVERS);
        var rivers = new HydrologyGenerator(PlannerProfile.V2, HydrologyProfile.FINITE_CONTINENT)
                .generate(seed, radius, 64.0, coast.coastline(), erodedIsland);
        metrics.finish("rivers");
        var erodedTerrain = new io.github.luoyan.adventureworldgen.terrain.TerrainMorphology(new HydrologyTerrain(erodedIsland, rivers));
        LOGGER.info("AdventureWorldGen building complete 16-block directed cost graph for {}", loaded.id());
        progress.stage(PlanningProgress.Stage.COSTS);
        var costs = new CostPlanner(PlannerProfile.V2).build(erodedTerrain, coast.coastline(), new Vec2(0.5, 0.5), progress.within(PlanningProgress.Stage.COSTS));
        metrics.finish("cost_graph");
        LOGGER.info("AdventureWorldGen cost graph has {} nodes and {} canonical edges for {}",
                costs.nodeCount(), costs.edgeStats().computations(), loaded.id());
        LOGGER.info("AdventureWorldGen jointly planning biome patches and structures for {}", loaded.id());
        progress.stage(PlanningProgress.Stage.PLACEMENT);
        var joint = new JointPlanner(PlannerProfile.V2).plan(seed, loaded.config(), erodedTerrain,
                (demand, x, y, z, structureSeed) -> {
                    var adapter = adapters.structure(demand.structureId()).orElseThrow(() ->
                            new PlanningFailure(PlanningFailure.Code.UNSUPPORTED_CONTENT, "structure-prepare",
                                    "no adapter for planned structure", java.util.Map.of("content_id", demand.structureId())));
                    var rotations = adapter.describe().rotations();
                    String rotation = rotations.get(Math.floorMod((int) structureSeed, rotations.size()));
                    var prepared = adapter.prepare(new io.github.luoyan.adventureworldgen.api.StructureAdapter.Candidate(
                            demand.instanceId(), x, y, z, rotation), structureSeed);
                    var errors = adapter.validatePrepared(prepared, erodedTerrain);
                    if (!errors.isEmpty()) throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,
                            "structure-prepare", "prepared structure failed validation",
                            java.util.Map.of("instance_id", demand.instanceId(), "errors", errors));
                    return new AdventurePlanView.PlannedStructure(demand.instanceId(), demand.structureId(), x, y, z,
                            rotation, prepared.entranceX(), prepared.entranceY(), prepared.entranceZ(),
                            prepared.footprint(), prepared.biomeProtection(), prepared.pieces());
                }, new JointPlanner.LevelConstraint() {
                    public boolean accepts(int level,int x,int z) { return true; }
                    public double penalty(int level,int x,int z) {
                        return io.github.luoyan.adventureworldgen.cost.AdventurePreference.penalty(level,
                                costs.normalizedPreferenceAt(x,z,loaded.config().world().radius()));
                    }
                }, (biome, x, z) -> adapters.biome(biome)
                        .compatibility(erodedTerrain.sample(x + 0.5, z + 0.5)).allowed(), progress.within(PlanningProgress.Stage.PLACEMENT), metrics::finish);
        GeneratedAdventurePlan plan = new GeneratedAdventurePlan(seed, loaded.config(), coast.coastline(), rivers,
                64.0, coast.landBand(), coast.seaBand(), "terrain-r22", joint.spawn(),
                joint.patches(), joint.structures(), new GeneratedAdventurePlan.PlanDiagnostics(
                coast.vertexCount(), rivers.channels().size(),
                rivers.channels().stream().mapToLong(channel -> channel.points().size()).sum(),
                (long) erosion.width() * erosion.height(), erosion.operationCount(),
                costs.nodeCount(), costs.edgeStats().computations(), joint.operationCount(),
                "terrain-r22+" + PlannerProfile.V2.hydrologyVersion() + "+erosion-v2"), erosion,capacities);
        metrics.finish("filler_and_transition");
        progress.stage(PlanningProgress.Stage.VALIDATION);
        for(var demand:new io.github.luoyan.adventureworldgen.planner.RequirementExpander().expandMinimum(loaded.config()).patches()) {
            var patch=plan.biomePatches().stream().filter(p->p.patchId().equals(demand.patchId())).findFirst().orElseThrow();
            long effective=plan.effectiveArea(patch);
            if(effective<demand.area().min())throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"effective-area",
                    "required dry biome area after mixing is insufficient",java.util.Map.of("patch",patch.patchId(),"effective_area",effective,"minimum",demand.area().min()));
        }
        metrics.finish("validation");
        metrics.adventure(joint.patches(),costs,radius);
        progress.stage(PlanningProgress.Stage.SAVE);
        try {
            repository.publishAtomically(worldDirectory, loaded.id(), codec.encode(loaded.id(), inputHash, plan), inputHash);
        } catch (IOException failure) {
            throw new IllegalStateException("could not atomically publish AdventureWorldGen plan", failure);
        }
        LOGGER.info("AdventureWorldGen plan READY for {} in {} ms", loaded.id(), (System.nanoTime() - started) / 1_000_000);
        metrics.finish("save");
        try { metrics.write(worldDirectory,seed,plan); }
        catch(IOException unavailable) { LOGGER.warn("Could not write planning timing diagnostics",unavailable); }
        return plan;
    }

    public static String inputHash(long seed, ProfileManager.LoadedProfile loaded, AdapterRegistry adapters) {
        String input = loaded.canonicalJson() + "\nseed=" + seed + "\nalgorithm=" + PlannerProfile.V2.algorithmVersion()
                + "\nimplementation=" + IMPLEMENTATION_REVISION
                + "\nhydrology=" + PlannerProfile.V2.hydrologyVersion() + "\nterrain=terrain-r22\nadapters="
                + String.join(",", adapters.versionKeys()) + "\ncost=directed-cost-16x8-v1\nerosion=ftf-erosion-block-units-v2";
        return AtomicPlanRepository.sha256(input.getBytes(StandardCharsets.UTF_8));
    }
}
