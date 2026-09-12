package io.github.luoyan.adventureworldgen.persistence;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.erosion.ErosionDeltaField;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.plan.BiomeLayout;
import io.github.luoyan.adventureworldgen.plan.PlanDiagnostics;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.terrain.RegionTerrain;
import io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan;
import io.github.luoyan.adventureworldgen.terrain.TerrainSettings;

import java.util.List;
import java.util.Objects;

/**
 * Everything a plan-v2 document freezes, as data.
 *
 * <p>This is the storage layer's whole contract: {@link PlanV2Codec} encodes and decodes this
 * record and never sees the executable plan object. The runtime builds one from the objects a first
 * planning run already produced, or restores a query object from a decoded one, so the two paths
 * share a single frozen vocabulary.
 *
 * <p>It lives in {@code persistence} rather than in {@code plan} because it references the domain
 * results the plan was built from ({@link Coastline}, {@link RiverNetwork},
 * {@link ErosionDeltaField}, {@link TerrainCapacityPlan}), and those packages already depend on
 * {@code plan}; the plan package would form a cycle. The storage layer is the lowest layer that
 * already sees every domain, so the frozen snapshot belongs here. The frozen <em>environment
 * state</em> itself ({@link BiomeLayout}) stays in {@code plan}.
 *
 * <p>{@code erosion} is null for plans built without an erosion field. {@code recipeSettings} and
 * {@code recipeRegions} record what the terrain recipe inputs were: a READY reload rejects a
 * profile whose settings no longer match them instead of silently regenerating terrain.
 */
public record PlanSnapshot(
        long seed,
        PlanDiagnostics diagnostics,
        AdventurePlanView.SpawnPosition spawn,
        Coastline coastline,
        RiverNetwork riverNetwork,
        double seaSurface,
        double landBand,
        double seaBand,
        String terrainVersion,
        TerrainSettings recipeSettings,
        List<RegionTerrain.Region> recipeRegions,
        List<PlannedBiomePatch> biomePatches,
        List<AdventurePlanView.PlannedStructure> structures,
        ErosionDeltaField erosion,
        TerrainCapacityPlan capacities,
        BiomeLayout biomeLayout
) {
    public PlanSnapshot {
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(spawn, "spawn");
        Objects.requireNonNull(coastline, "coastline");
        Objects.requireNonNull(riverNetwork, "riverNetwork");
        Objects.requireNonNull(terrainVersion, "terrainVersion");
        Objects.requireNonNull(recipeSettings, "recipeSettings");
        Objects.requireNonNull(capacities, "capacities");
        Objects.requireNonNull(biomeLayout, "biomeLayout");
        recipeRegions = List.copyOf(recipeRegions);
        biomePatches = List.copyOf(biomePatches);
        structures = List.copyOf(structures);
    }
}
