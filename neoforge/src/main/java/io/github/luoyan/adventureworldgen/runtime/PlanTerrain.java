package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.erosion.ErodedTerrain;
import io.github.luoyan.adventureworldgen.erosion.ErosionDeltaField;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyTerrain;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.terrain.ExactGridTerrain;
import io.github.luoyan.adventureworldgen.terrain.IslandMacroTerrain;
import io.github.luoyan.adventureworldgen.terrain.RegionTerrain;
import io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan;
import io.github.luoyan.adventureworldgen.terrain.TerrainMorphology;
import io.github.luoyan.adventureworldgen.plan.PlanVersions;

/**
 * The terrain stack a plan queries, assembled in one place for both entries.
 *
 * <p>Composition order is continuous regions, then the island macro surface, then erosion, then
 * hydrology, then the composed morphology. First planning computes erosion and hydrology between
 * those steps (each one is an input to the next stage) and hands the objects it already built to
 * the plan; READY restore recomputes the same stack from frozen data. Both go through this record,
 * so the order and the wrappers cannot drift apart unnoticed.
 *
 * <p>Both entries share the formula and this order; they differ only in whether the query terrain
 * carries a half-block memoizing wrapper. First planning reuses the wrapper its own queries already
 * warmed, for the rest of assembly, the final check and the runtime queries that follow. A READY
 * reload starts from the plain morphology: the wrapper's saving is not established for that path,
 * so it does not create a second, plan-sized cache. Both paths keep the plan's own 16384-entry
 * column caches.
 *
 * <p>The wrapper is a cache and never a formula: {@code PlanTerrainTest} pins that both answer
 * identically, off-grid coordinates included, so this difference can never change a sampled result.
 */
record PlanTerrain(RegionTerrain regions, MacroTerrain island, HydrologyTerrain water, MacroTerrain terrain) {
    /** Regions and the island surface: exactly the inputs erosion and hydrology are generated from. */
    record Foundation(RegionTerrain regions, MacroTerrain island) {
        /** The surface hydrology reads: the island, wrapped in the erosion field when there is one. */
        MacroTerrain eroded(ErosionDeltaField erosion) {
            return erosion == null ? island : new ErodedTerrain(island, erosion, PlanVersions.EROSION);
        }
    }

    /** Builds the frozen region/island foundation with the profile the plan was started from. */
    static Foundation foundation(PlannerProfile profile, long seed, AdventureWorldConfig config,
                                 TerrainCapacityPlan capacities,
                                 Coastline coastline, double seaSurface, double landBand, double seaBand,
                                 String terrainVersion) {
        RegionTerrain regions = new RegionTerrain(seed, profile, capacities, config.world().terrain(),
                config.fillerTerrainPolicy());
        MacroTerrain island = new IslandMacroTerrain(coastline, regions, seed, seaSurface, landBand, seaBand, terrainVersion);
        return new Foundation(regions, island);
    }

    static PlanTerrain compose(Foundation foundation, MacroTerrain eroded, RiverNetwork riverNetwork) {
        HydrologyTerrain water = new HydrologyTerrain(eroded, riverNetwork);
        return new PlanTerrain(foundation.regions(), foundation.island(), water, new TerrainMorphology(water));
    }

    /** The whole stack from frozen data: what a READY reload assembles. */
    static PlanTerrain assemble(PlannerProfile profile, long seed, AdventureWorldConfig config,
                                TerrainCapacityPlan capacities,
                                Coastline coastline, RiverNetwork riverNetwork, double seaSurface, double landBand,
                                double seaBand, String terrainVersion, ErosionDeltaField erosion) {
        Foundation foundation = foundation(profile, seed, config, capacities, coastline, seaSurface, landBand,
                seaBand, terrainVersion);
        return compose(foundation, foundation.eroded(erosion), riverNetwork);
    }

    /**
     * The query terrain the first planning run hands to the plan: a half-block memoizing wrapper
     * around the morphology, already warm from planning. This is an accepted optimization, not a
     * requirement — a READY reload reads the plain morphology and gets the same answers without
     * carrying the extra cache.
     */
    PlanTerrain withMemoizedQueries() {
        return new PlanTerrain(regions, island, water, new ExactGridTerrain(terrain, 262144));
    }
}
