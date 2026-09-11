package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.StructureAdapter;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.planner.JointPlanner;
import io.github.luoyan.adventureworldgen.planner.RequirementExpander;

import java.util.Map;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/**
 * The structure side of the adapter bridge: the planner states a structure demand, the registered
 * adapter prepares and validates a concrete placement, and this class returns the frozen structure.
 *
 * <p>It is kept out of the session orchestrator so structure preparation is one named unit that can
 * change without touching stage order, budgets or persistence, and so the two adapter queries the
 * planner needs before and during placement live together: the spawn reservation radius that coast
 * generation must keep free, and the frozen structure itself.
 *
 * <p>Every structure here comes from an adapter registration. There is no built-in structure
 * branch, so a new structure is supported by registering an adapter.
 */
final class StructureAdapterBridge implements JointPlanner.StructureFreezer {
    private final AdapterRegistry adapters;
    private final MacroTerrain terrain;

    StructureAdapterBridge(AdapterRegistry adapters, MacroTerrain terrain) {
        this.adapters = adapters;
        this.terrain = terrain;
    }

    /**
     * Radius the selected spawn structure needs around its spawn point, or zero when the profile
     * reserves no spawn structure. Evaluated before coast generation so the coast keeps it free.
     */
    static double spawnReservationRadius(AdapterRegistry adapters, AdventureWorldConfig config) {
        if (!config.spawn().hasStructure()) return 0.0;
        return adapters.structure(config.spawn().structure().id()).orElseThrow(() ->
                new PlanningFailure(PlanningFailure.Code.UNSUPPORTED_CONTENT, FailureStage.SPAWN_RESERVATION,
                        "spawn structure has no adapter", Map.of("content_id",
                        config.spawn().structure().id()))).describe().maximumFootprintRadius()
                + StrictMath.hypot(config.spawn().structure().spawnPoint().x(),
                config.spawn().structure().spawnPoint().z());
    }

    @Override public AdventurePlanView.PlannedStructure freeze(RequirementExpander.StructureInstanceDemand demand,
                                                               int x, int y, int z, long structureSeed) {
        var adapter = adapters.structure(demand.structureId()).orElseThrow(() ->
                new PlanningFailure(PlanningFailure.Code.UNSUPPORTED_CONTENT, FailureStage.STRUCTURE_PREPARE,
                        "no adapter for planned structure", Map.of("content_id", demand.structureId())));
        var rotations = adapter.describe().rotations();
        String rotation = rotations.get(Math.floorMod((int) structureSeed, rotations.size()));
        var prepared = adapter.prepare(new StructureAdapter.Candidate(
                demand.instanceId(), x, y, z, rotation), structureSeed);
        var errors = adapter.validatePrepared(prepared, terrain);
        if (!errors.isEmpty()) throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, FailureStage.STRUCTURE_PREPARE, "prepared structure failed validation",
                Map.of("instance_id", demand.instanceId(), "errors", errors));
        return new AdventurePlanView.PlannedStructure(demand.instanceId(), demand.structureId(), x, y, z,
                rotation, prepared.entranceX(), prepared.entranceY(), prepared.entranceZ(),
                prepared.footprint(), prepared.biomeProtection(), prepared.pieces());
    }
}
