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

/**
 * The terrain stack a plan queries, assembled in one place for both entries.
 *
 * <p>Composition order is continuous regions, then the island macro surface, then erosion, then
 * hydrology, then the composed morphology. First planning computes erosion and hydrology between
 * those steps (each one is an input to the next stage) and hands the objects it already built to
 * the plan; READY restore recomputes the same stack from frozen data. Both go through this record,
 * so the order and the wrappers cannot drift apart unnoticed.
 *
 * <p>The query terrain is the one step the two entries still differ on, and it is deliberate:
 * first planning keeps a half-block memoizing wrapper warm from its own planning queries, restore
 * uses the plain morphology. The wrapper is a cache, not a formula — sampled results are identical
 * (see {@code PlanV2CodecTest}) — but the object graph is not, and unifying it is an open decision
 * that needs a performance comparison.
 */
record PlanTerrain(RegionTerrain regions, MacroTerrain island, HydrologyTerrain water, MacroTerrain terrain) {
    /** Regions and the island surface: exactly the inputs erosion and hydrology are generated from. */
    record Foundation(RegionTerrain regions, MacroTerrain island) {
        /** The surface hydrology reads: the island, wrapped in the erosion field when there is one. */
        MacroTerrain eroded(ErosionDeltaField erosion) {
            return erosion == null ? island : new ErodedTerrain(island, erosion, "erosion-v2");
        }
    }

    static Foundation foundation(long seed, AdventureWorldConfig config, TerrainCapacityPlan capacities,
                                 Coastline coastline, double seaSurface, double landBand, double seaBand,
                                 String terrainVersion) {
        RegionTerrain regions = new RegionTerrain(seed, PlannerProfile.V2, capacities, config.world().terrain(), config);
        MacroTerrain island = new IslandMacroTerrain(coastline, regions, seed, seaSurface, landBand, seaBand, terrainVersion);
        return new Foundation(regions, island);
    }

    static PlanTerrain compose(Foundation foundation, MacroTerrain eroded, RiverNetwork riverNetwork) {
        HydrologyTerrain water = new HydrologyTerrain(eroded, riverNetwork);
        return new PlanTerrain(foundation.regions(), foundation.island(), water, new TerrainMorphology(water));
    }

    /** The whole stack from frozen data: what a READY reload assembles. */
    static PlanTerrain assemble(long seed, AdventureWorldConfig config, TerrainCapacityPlan capacities,
                                Coastline coastline, RiverNetwork riverNetwork, double seaSurface, double landBand,
                                double seaBand, String terrainVersion, ErosionDeltaField erosion) {
        Foundation foundation = foundation(seed, config, capacities, coastline, seaSurface, landBand, seaBand, terrainVersion);
        return compose(foundation, foundation.eroded(erosion), riverNetwork);
    }

    /**
     * The memoizing query wrapper the first planning run hands to the plan, so the validation stage
     * and the immediate runtime queries reuse the samples planning already took. Caching only.
     */
    PlanTerrain withMemoizedQueries() {
        return new PlanTerrain(regions, island, water, new ExactGridTerrain(terrain, 262144));
    }
}
