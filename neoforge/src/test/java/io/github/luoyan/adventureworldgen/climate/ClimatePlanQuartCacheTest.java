package io.github.luoyan.adventureworldgen.climate;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.plan.PlanningObserver;
import io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The frozen climate memo is keyed by quart cell, so its value must be a pure function of the
 * coordinate and the frozen state.
 *
 * <p>The public queries take a {@code MacroSample} because their callers already hold one. At a
 * quart center that argument must not decide the cell: the biome rules quantize a coordinate to its
 * cell centre and then pass the sample of the <em>original</em> position, so the old implementation
 * let whichever call arrived first fix the whole 4-block cell at that first sample's elevation.
 * These checks pin the corrected contract, including negative coordinates, cell boundaries and a
 * READY-style restore.
 */
class ClimatePlanQuartCacheTest {
    private static final int QUART = 4;

    private static AdventureWorldConfig config() {
        return new AdventureWorldConfigParser().parse("""
                {"world":{"radius":512},"spawn":{"biome":"minecraft:plains"},"biomes":{
                 "filler":["minecraft:plains","minecraft:desert"],
                 "terrain_rules":{"minecraft:plains":{"humidities":{"medium":1}},
                                  "minecraft:desert":{"humidities":{"dry":1}}}}}
                """);
    }

    /** Ground surface varies with both axes, so a sample taken elsewhere is genuinely a different sample. */
    private static final MacroTerrain TERRAIN = (x, z) -> new MacroSample(
            72 + 24 * StrictMath.sin(x * 0.01) + 11 * StrictMath.cos(z * 0.013),
            Double.NaN, WaterKind.NONE, false, "r", "plains", "test");

    private static ClimatePlan plan(long seed) {
        return new ClimatePlan(seed, config(), TERRAIN, ignored -> {}, PlanningObserver.NONE, null,
                new ClimateDiagnostics(config(), ClimatePlan.STEP));
    }

    private static void assertSameAsCenterSample(ClimatePlan plan, int x, int z) {
        double atCenter = plan.valueAt(x, z, TERRAIN.sample(x, z));
        // A sample from a position three cells away, and one from the neighbouring cell.
        double fromFar = plan.valueAt(x, z, TERRAIN.sample(x + 300, z - 411));
        double fromNeighbour = plan.valueAt(x, z, TERRAIN.sample(x + QUART, z));
        assertEquals(atCenter, fromFar, 0.0, "a foreign sample decided the cell at " + x + "," + z);
        assertEquals(atCenter, fromNeighbour, 0.0, "a neighbouring sample decided the cell at " + x + "," + z);
        assertEquals(plan.typeAt(x, z, TERRAIN.sample(x + 300, z - 411)), plan.typeAt(x, z, TERRAIN.sample(x, z)),
                "the temperature type must not depend on the sample passed in");
    }

    @Test
    void aQuartCellIsDecidedByTheFrozenTerrainNotByTheFirstSample() {
        var plan = plan(7331);
        assertSameAsCenterSample(plan, 2, 2);
        assertSameAsCenterSample(plan, -2, 2);
        assertSameAsCenterSample(plan, 2, -2);
        assertSameAsCenterSample(plan, 254, 130);
    }

    @Test
    void theFirstQueryOrderCannotChangeACell() {
        var forward = plan(7331);
        // Seed every cell through a foreign sample first...
        for (int x = -254; x <= 254; x += QUART) forward.valueAt(x, 2, TERRAIN.sample(x + 200, 2));
        // ...then re-read them through the centre samples and expect the same values.
        for (int x = -254; x <= 254; x += QUART)
            assertEquals(forward.valueAt(x, 2, TERRAIN.sample(x + 200, 2)),
                    forward.valueAt(x, 2, TERRAIN.sample(x, 2)), 0.0, "x=" + x);

        var backward = plan(7331);
        for (int x = 254; x >= -254; x -= QUART) backward.valueAt(x, 2, TERRAIN.sample(x, 2));
        for (int x = -254; x <= 254; x += QUART)
            assertEquals(forward.valueAt(x, 2, TERRAIN.sample(x, 2)),
                    backward.valueAt(x, 2, TERRAIN.sample(x, 2)), 0.0,
                    "different query order and first sample must agree");
    }

    @Test void fractionalCoordinatesNeverPoisonTemperatureOrHumidityCentres() {
        var polluted=plan(7331);var clean=plan(7331);
        for(int x:new int[]{-254,-2,2,254}) {
            polluted.valueAt(x+.1,2,TERRAIN.sample(x+300,2));
            polluted.humidity().valueAt(x+.1,2,TERRAIN.sample(x+300,2));
            assertEquals(clean.valueAt(x,2,TERRAIN.sample(x,2)),polluted.valueAt(x,2,TERRAIN.sample(x+300,2)));
            assertEquals(clean.humidity().valueAt(x,2,TERRAIN.sample(x,2)),polluted.humidity().valueAt(x,2,TERRAIN.sample(x+300,2)));
        }
    }

    @Test
    void floorQuantizationCoversNegativeCoordinatesAndCellBoundaries() {
        var plan = plan(7331);
        // Centers are 4k + 2. Negative centers and centers sitting on a 4k boundary both resolve.
        for (int x : new int[]{-258, -254, -130, -2, 2, 130, 254, 258}) assertSameAsCenterSample(plan, x, 2);
        // Off-grid coordinates keep their exact coordinates and are never memoised, so they may
        // differ from the cell centre - that is the documented "continuous queries stay exact" rule.
        double offGrid = plan.valueAt(1.5, 2, TERRAIN.sample(1.5, 2));
        assertEquals(plan.valueAt(1.5, 2, TERRAIN.sample(1.5, 2)), offGrid, 0.0);
    }

    @Test
    void aRestoredFrozenPlanAnswersCentresTheSameWayAsTheFirstPlanning() {
        var planned = plan(7331);
        var state = planned.snapshot();
        var restored = new ClimatePlan(7331, config(), TERRAIN, ignored -> {}, PlanningObserver.NONE, state,
                new ClimateDiagnostics(config(), ClimatePlan.STEP));
        for (int x : new int[]{-254, -2, 2, 130, 254}) {
            assertEquals(planned.valueAt(x, 2, TERRAIN.sample(x, 2)),
                    restored.valueAt(x, 2, TERRAIN.sample(x, 2)), 0.0,
                    "first planning and READY restore disagreed at x=" + x);
        }
    }
}
