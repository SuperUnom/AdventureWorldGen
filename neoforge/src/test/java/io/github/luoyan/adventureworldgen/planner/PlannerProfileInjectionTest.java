package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules;
import io.github.luoyan.adventureworldgen.climate.ClimatePlan;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.plan.PlanningObserver;
import io.github.luoyan.adventureworldgen.runtime.PlanIdentity;
import io.github.luoyan.adventureworldgen.config.LoadedProfile;
import io.github.luoyan.adventureworldgen.worldgen.GenericBiomeAdapter;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An injected profile must reach every sub-stage, not just the entry point.
 *
 * <p>Several classes used to read {@code PlannerProfile.V2} directly, so a differently budgeted or
 * re-versioned profile would have been silently ignored by exactly the stages that enforce the
 * budgets - the failure would have appeared somewhere else entirely, or not at all. Each check here
 * injects a profile whose relevant value is small enough to observe and asserts that the stage
 * reports <em>that</em> value.
 *
 * <p>The injected profiles deliberately carry a distinct {@code algorithmVersion}: the identity
 * records the algorithm and hydrology identities that planned the world, so a budget change that
 * leaves those markers alone would hash the same as V2 while planning differently.
 */
class PlannerProfileInjectionTest {
    private static final ContentId PLAINS = new ContentId("test:plains");

    private static AdventureWorldConfig config() {
        return new AdventureWorldConfigParser().parse("""
                {"world":{"radius":512},"spawn":{"biome":"test:plains"},
                 "biomes":{"filler":["test:plains"]},"structures":[]}
                """);
    }

    /** Differs from V2 in identity and in every budget the placement and filler stages read. */
    private static PlannerProfile injected() {
        return PlannerProfile.V2
                .withAlgorithmVersion("planner-v2-injected-probe")
                .withBudgets(64, 1L << 30)
                .withCompetitiveGrowthOperations(3);
    }

    private static final MacroTerrain FLAT = (x, z) ->
            new MacroSample(80, Double.NaN, WaterKind.NONE, false, "r", "plains", "test");

    @Test
    void placementIndexEnforcesTheInjectedNodeBudget() {
        var failure = assertThrows(PlanningFailure.class,
                () -> new PlacementIndex(config(), FLAT, (level, x, z) -> true, (biome, x, z) -> true, injected()));
        assertEquals(PlanningFailure.Code.RESOURCE_LIMIT, failure.code());
        assertEquals("placement-index", failure.stage());
        assertEquals("64", failure.diagnostics().get("maximum_nodes"),
                "the failure must quote the injected budget, not the V2 one");
    }

    @Test
    void fillerEnforcesTheInjectedNodeBudget() {
        var config = config();
        var climate = new ClimatePlan(7331, config, FLAT, ignored -> {}, PlanningObserver.NONE, null,
                new ClimateDiagnostics(config, ClimatePlan.STEP));
        var rules = new BiomeEnvironmentRules(config, climate);
        var failure = assertThrows(PlanningFailure.class,
                () -> new FillerLayout(injected(), 7331, config, FLAT, List.of(), rules));
        assertEquals(PlanningFailure.Code.RESOURCE_LIMIT, failure.code());
        assertEquals("filler", failure.stage());
        assertEquals("64", failure.diagnostics().get("maximum_cells"));
    }

    @Test
    void biomeAllocationEnforcesTheInjectedGrowthBudget() {
        var config = config();
        // The index is deliberately built with V2: this check is about the growth budget, and a
        // 64-node placement budget would fail earlier and hide it.
        var index = new PlacementIndex(config, FLAT, (level, x, z) -> true, (biome, x, z) -> true,
                PlannerProfile.V2);
        var demands = new RequirementExpander().expandMinimum(config).patches();
        var climate = new ClimatePlan(7331, config, FLAT, ignored -> {}, PlanningObserver.NONE, null,
                new ClimateDiagnostics(config, ClimatePlan.STEP));
        var rules = new BiomeEnvironmentRules(config, climate);
        // Three growth operations cannot cover a patch, so the injected budget must be the one that
        // runs out - and it must be named with its unit in the diagnostic.
        var failure = assertThrows(PlanningFailure.class, () -> new BiomeAllocationPlanner(injected())
                .allocate(7331, config, index, demands, List.of(), rules, PlanningObserver.NONE, ignored -> {}));
        assertEquals(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED, failure.code());
        assertEquals("competitive-growth", failure.stage());
        assertEquals("3", failure.diagnostics().get("budget"), "the reported budget is the injected one");
    }

    @Test
    void capacitySolverGeometryFollowsTheInjectedTerrainProfile() {
        var config = new AdventureWorldConfigParser().parse("""
                {"world":{"radius":2048},"spawn":{"biome":"test:plains"},
                 "biomes":{"filler":["test:plains"]},"structures":[]}
                """);
        var coast = new Coastline(List.of(new Vec2(-2048, -2048), new Vec2(2048, -2048),
                new Vec2(2048, 2048), new Vec2(-2048, 2048)));
        var wide = PlannerProfile.V2;
        var narrow = PlannerProfile.V2.withTerrain(new PlannerProfile.Terrain(256,
                wide.terrain().maximumRegionJitterFraction(), wide.terrain().coordinateWarpScale(),
                wide.terrain().coordinateWarpAmplitude(), wide.terrain().templateWeights(),
                wide.terrain().detailRecoveryDistance(), wide.terrain().maximumSupportDelta()));
        var widePlan = TerrainCapacitySolver.reserve(wide, 7331, config, coast, 64);
        var narrowPlan = TerrainCapacitySolver.reserve(narrow, 7331, config, coast, 64);
        assertNotEquals(widePlan.reservations(), narrowPlan.reservations(),
                "the region spacing of the injected profile must describe the regions it reserves for");
        // Determinism is unaffected by the injection.
        assertEquals(widePlan.reservations(), TerrainCapacitySolver.reserve(wide, 7331, config, coast, 64).reservations());
    }

    @Test
    void planIdentityFollowsTheInjectedAlgorithmVersion() {
        var config = config();
        var loaded = new LoadedProfile(new ContentId("adventureworldgen:injected"), config, "{\"probe\":1}", "probe-pack");
        var adapters = AdapterRegistry.builder(new GenericBiomeAdapter()).build();
        String baseline = PlanIdentity.hash(7L, loaded, adapters, PlannerProfile.V2);
        assertEquals(baseline, PlanIdentity.hash(7L, loaded, adapters, PlannerProfile.V2));
        assertNotEquals(baseline, PlanIdentity.hash(7L, loaded, adapters, injected()),
                "a profile with its own version marker must not hash like V2");
        assertTrue(baseline.matches("[0-9a-f]{64}"));
    }
}
