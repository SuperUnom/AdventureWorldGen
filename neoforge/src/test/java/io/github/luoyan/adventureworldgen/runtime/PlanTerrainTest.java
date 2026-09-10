package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The terrain stack is assembled once for both entries. First planning and READY restore differ on
 * exactly one thing: whether the query terrain is the plain morphology or a memoizing wrapper.
 * These checks pin that the wrapper is a cache and not a formula, and that the composition itself
 * is the same either way.
 */
class PlanTerrainTest {
    private static final long SEED = 0x5EED_BA5EL;
    private static final String CONFIG = """
            {"world":{"radius":128},"spawn":{"biome":"test:plains"},"biomes":{"filler":["test:plains"]}}
            """;
    private static final Coastline COAST = new Coastline(List.of(new Vec2(-64, -64), new Vec2(64, -64),
            new Vec2(64, 64), new Vec2(-64, 64)));
    private static final RiverNetwork NO_RIVERS =
            new RiverNetwork(List.of(), List.of(), PlannerProfile.V2.hydrologyVersion());

    @Test
    void theMemoizingWrapperIsACacheNotAFormula() {
        var plain = stack(null);
        var memoized = plain.withMemoizedQueries();

        assertNotSame(plain.terrain(), memoized.terrain());
        assertSame(plain.water(), memoized.water(), "only the query terrain may differ");
        for (int x = -100; x <= 100; x += 7) {
            for (int z = -100; z <= 100; z += 7) {
                MacroSample blockCentre = plain.terrain().sample(x + 0.5, z + 0.5);
                assertEquals(blockCentre, memoized.terrain().sample(x + 0.5, z + 0.5), "block centre at " + x + "/" + z);
                assertEquals(blockCentre, memoized.terrain().sample(x + 0.5, z + 0.5), "a cache hit must repeat it");
                assertEquals(plain.terrain().sample(x, z), memoized.terrain().sample(x, z), "integer column at " + x + "/" + z);
                assertEquals(plain.terrain().sample(x + 0.25, z - 1.75), memoized.terrain().sample(x + 0.25, z - 1.75),
                        "the cache must not quantize off-grid coordinates");
            }
        }
    }

    @Test
    void withoutAnErosionFieldHydrologyReadsTheIslandItself() {
        var foundation = foundation();
        assertSame(foundation.island(), foundation.eroded(null), "no erosion means no extra wrapper");
        assertNotSame(foundation.island(), foundation.eroded(erosionField()));
    }

    @Test
    void theFrozenEntryAndTheStagedCompositionBuildTheSameStack() {
        var foundation = foundation();
        var staged = PlanTerrain.compose(foundation, foundation.eroded(null), NO_RIVERS);
        var frozen = PlanTerrain.assemble(SEED, config(), TerrainCapacityPlan.empty(), COAST, NO_RIVERS,
                64.0, 32.0, 64.0, "terrain-test", null);

        assertSame(foundation.regions().getClass(), frozen.regions().getClass());
        for (int x = -60; x <= 60; x += 11)
            for (int z = -60; z <= 60; z += 11)
                assertEquals(staged.terrain().sample(x + 0.5, z + 0.5), frozen.terrain().sample(x + 0.5, z + 0.5),
                        "the two entries must compose identical surfaces at " + x + "/" + z);
    }

    private static PlanTerrain stack(io.github.luoyan.adventureworldgen.erosion.ErosionDeltaField erosion) {
        var foundation = foundation();
        return PlanTerrain.compose(foundation, foundation.eroded(erosion), NO_RIVERS);
    }

    private static PlanTerrain.Foundation foundation() {
        return PlanTerrain.foundation(SEED, config(), TerrainCapacityPlan.empty(), COAST, 64.0, 32.0, 64.0,
                "terrain-test");
    }

    private static io.github.luoyan.adventureworldgen.erosion.ErosionDeltaField erosionField() {
        return new io.github.luoyan.adventureworldgen.erosion.ErosionDeltaField(-8, -8, 8, 3, 3,
                new float[9], 0);
    }

    private static AdventureWorldConfig config() {
        return new AdventureWorldConfigParser().parse(CONFIG);
    }
}
