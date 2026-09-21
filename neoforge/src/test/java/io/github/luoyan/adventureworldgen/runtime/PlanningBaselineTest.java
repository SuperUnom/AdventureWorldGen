package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.cost.CostPlanner;
import io.github.luoyan.adventureworldgen.erosion.ErodedTerrain;
import io.github.luoyan.adventureworldgen.erosion.ErosionDeltaField;
import io.github.luoyan.adventureworldgen.erosion.ErosionGenerator;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyGenerator;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyProfile;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyTerrain;
import io.github.luoyan.adventureworldgen.persistence.AtomicPlanRepository;
import io.github.luoyan.adventureworldgen.persistence.PlanV2Codec;
import io.github.luoyan.adventureworldgen.planner.JointPlanner;
import io.github.luoyan.adventureworldgen.plan.PlanDiagnostics;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.PlanningObserver;
import io.github.luoyan.adventureworldgen.plan.StructurePlanningCatalog;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.CoastGenerator;
import io.github.luoyan.adventureworldgen.terrain.ExactGridTerrain;
import io.github.luoyan.adventureworldgen.terrain.IslandMacroTerrain;
import io.github.luoyan.adventureworldgen.terrain.RegionTerrain;
import io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan;
import io.github.luoyan.adventureworldgen.terrain.TerrainMorphology;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import io.github.luoyan.adventureworldgen.planner.TerrainCapacitySolver;
import io.github.luoyan.adventureworldgen.testing.TerrainSnapshots;

/**
 * P0 reproducibility baseline for the structural refactor.
 *
 * <p>This drives the same stage order as {@link RuntimePlanner} without any Minecraft
 * registry, adapter or client dependency, then pins everything the refactor must not
 * change: the canonical plan-v3 bytes, the READY round trip, and sampled terrain /
 * water / biome / structure / spawn observations. Coverage of the deterministic
 * planning path lives here so a purely structural change that alters output fails
 * loudly instead of being absorbed by a stale expectation.
 */
class PlanningBaselineTest {
    private static final long SEED = 0x5EED_BA5EL;
    private static final ContentId PROFILE = new ContentId("adventureworldgen:refactor-baseline");
    private static final String INPUT_HASH = "refactor-baseline-input";

    private static final String CONFIG = """
            {"world":{"radius":512},
             "spawn":{"biome":"test:plains"},
             "biomes":{"required":[{"id":"test:forest","adventure_level":3,"area":{"min":4096,"target":8192}}],
                       "filler":["test:plains","test:forest","test:desert"]},
             "structures":[{"id":"test:keep","adventure_level":4,"count":{"min":1,"max":1},
                            "allowed_biomes":{"id":["test:desert"],"area":{"min":4096,"target":8192}}}]}
            """;

    @Test
    void canonicalPlanBytesRoundTripAndSampledFieldsMatchTheRecordedBaseline() throws Exception {
        var config = new AdventureWorldConfigParser().parse(CONFIG);
        var codec = new PlanV2Codec();
        var plan = buildPlan(config);

        byte[] encoded = codec.encode(PROFILE, INPUT_HASH, plan.snapshot());
        String planHash = sha256(encoded);
        // Roads are disabled in this fixture: v5 changes only the format marker. Keep the v4
        // payload hash as evidence that no terrain, layout, spawn or empty-road field changed.
        var previous=com.google.gson.JsonParser.parseString(new String(encoded,StandardCharsets.UTF_8)).getAsJsonObject();
        previous.addProperty("format","plan-v4");
        assertEquals("ea1edcf95ac6998135434c36a920d277b3dd52c954829b039df15df3fa1f8186",
                sha256(previous.toString().getBytes(StandardCharsets.UTF_8)),"unexpected change beyond the v5 format marker");
        // The format adds only an empty roads object for this roads-disabled fixture. Prove the
        // previous canonical payload is byte-identical after removing that reviewed envelope change.
        var legacy = com.google.gson.JsonParser.parseString(new String(encoded, StandardCharsets.UTF_8)).getAsJsonObject();
        legacy.remove("roads"); legacy.addProperty("format", "plan-v3");
        assertEquals("b5397002aae053d06bc01c224c455891a95cbf216052777fd483be051ade0d97",
                sha256(legacy.toString().getBytes(StandardCharsets.UTF_8)), "roads changed pre-existing frozen fields");

        // READY reload must reproduce the same canonical bytes: restore never re-plans.
        GeneratedAdventurePlan reloaded = GeneratedAdventurePlan.restore(config,
                codec.decode(encoded, PROFILE, INPUT_HASH));
        byte[] reencoded = codec.encode(PROFILE, INPUT_HASH, reloaded.snapshot());
        assertArrayEquals(encoded, reencoded, "READY reload changed the canonical plan bytes");

        assertEquals(List.of(EXPECTED_PLAN_SHA256, EXPECTED_FIELD_SHA256, EXPECTED_SPAWN),
                List.of(planHash, sampleFields(plan), spawnSignature(plan)),
                "recorded planning baseline changed");
    }

    @Test
    void sharedBiomeAndStructureAnchorsSurviveReadyRoundTrip() throws Exception {
        // Keep the old non-merging golden fixture above; exercise new behavior independently.
        var config = new AdventureWorldConfigParser().parse(CONFIG.replace(
                "\"id\":[\"test:desert\"]", "\"id\":[\"test:forest\"]"));
        var plan = buildPlan(config);
        var demands = new io.github.luoyan.adventureworldgen.planner.RequirementExpander().expandMinimum(config);
        assertEquals(2, demands.patches().size());
        var placement = plan.plannedStructures().getFirst();
        String sharedId = demands.carrierPatchId(placement.instanceId());
        var shared = plan.snapshot().biomePatches().stream().filter(p -> p.patchId().equals(sharedId)).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(shared.contains(placement.anchorX(), placement.anchorZ()));
        var codec = new PlanV2Codec();
        byte[] encoded = codec.encode(PROFILE, INPUT_HASH, plan.snapshot());
        var restored = GeneratedAdventurePlan.restore(config, codec.decode(encoded, PROFILE, INPUT_HASH));
        assertArrayEquals(encoded, codec.encode(PROFILE, INPUT_HASH, restored.snapshot()));
        assertEquals(plan.plannedStructures(), restored.plannedStructures());
        assertEquals(sampleFields(plan), sampleFields(restored));
    }

    /** Mirrors RuntimePlanner's stage order and object reuse without its Minecraft adapters. */
    private static GeneratedAdventurePlan buildPlan(AdventureWorldConfig config) {
        double radius = config.world().radius();
        double spawnRadius = StrictMath.min(256.0, radius / 10.0);
        double keep = spawnRadius + StrictMath.min(32.0, radius / 20.0);

        var coast = new CoastGenerator(PlannerProfile.V2).generate(SEED, radius, keep);
        var capacities = TerrainCapacitySolver.reserve(PlannerProfile.V2, SEED, config, coast.coastline(), coast.landBand());
        var foundation = PlanTerrain.foundation(PlannerProfile.V2, SEED, config, capacities, coast.coastline(), 64.0,
                coast.landBand(), coast.seaBand(), TerrainSnapshots.PRODUCTION_TERRAIN);

        int erosionSpacing = 8;
        int erosionExtent = (int) StrictMath.ceil((radius + 256.0) / erosionSpacing) * erosionSpacing;
        int erosionSize = erosionExtent * 2 / erosionSpacing + 1;
        ErosionDeltaField erosion = new ErosionGenerator(PlannerProfile.V2, HydrologyProfile.FINITE_CONTINENT)
                .generate(SEED, foundation.island(), -erosionExtent, -erosionExtent, erosionSpacing, erosionSize, erosionSize);
        var erodedIsland = foundation.eroded(erosion);
        var rivers = new HydrologyGenerator(PlannerProfile.V2, HydrologyProfile.FINITE_CONTINENT)
                .generate(SEED, radius, 64.0, coast.coastline(), erodedIsland);
        var planningTerrain = PlanTerrain.compose(foundation, erodedIsland, rivers).withMemoizedQueries();
        MacroTerrain erodedTerrain = planningTerrain.terrain();
        var costs = new CostPlanner(PlannerProfile.V2).build(erodedTerrain, coast.coastline(), new Vec2(0.5, 0.5));

        var jointPlanner = new JointPlanner(PlannerProfile.V2);
        var joint = jointPlanner.plan(SEED, config, erodedTerrain,
                StructurePlanningCatalog.fromIds(config.structures().stream()
                        .map(AdventureWorldConfig.StructureSettings::id).toList()),
                JointPlanner.preferenceOnly((level, x, z) ->
                        io.github.luoyan.adventureworldgen.cost.AdventurePreference.penalty(level,
                                costs.normalizedPreferenceAt(x, z, radius))),
                (biome, x, z) -> true, ignored -> { }, ignored -> { });

        return GeneratedAdventurePlan.fromPlanning(PlannerProfile.V2, SEED, config, coast.coastline(), rivers, 64.0,
                coast.landBand(), coast.seaBand(), TerrainSnapshots.PRODUCTION_TERRAIN, joint.spawn(),
                joint.patches(), joint.structures(),
                new PlanDiagnostics(coast.vertexCount(), rivers.channels().size(),
                        rivers.channels().stream().mapToLong(channel -> channel.points().size()).sum(),
                        (long) erosion.width() * erosion.height(), erosion.operationCount(),
                        costs.nodeCount(), costs.edgeStats().computedPairs(), joint.operationCount(),
                        TerrainSnapshots.productionDiagnostics(PlannerProfile.V2.hydrologyVersion())),
                erosion, capacities,
                new GeneratedAdventurePlan.PlanningInputs(planningTerrain, jointPlanner.climate()),
                PlanningObserver.NONE);
    }

    private static String spawnSignature(GeneratedAdventurePlan plan) {
        var spawn = plan.spawnPosition();
        return spawn.x() + "/" + spawn.z() + "/" + spawn.yaw();
    }

    /** Sampled terrain, water, biome, structure and spawn observations across the domain. */
    private static String sampleFields(GeneratedAdventurePlan plan) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int x = -416; x <= 416; x += 32) {
            for (int z = -416; z <= 416; z += 32) {
                var sample = plan.terrainAt(x + 0.5, z + 0.5);
                digest.update((x + "|" + z + "|" + plan.biomeAt(x, 64, z) + "|"
                        + Double.doubleToRawLongBits(sample.groundSurface()) + "|"
                        + Double.doubleToRawLongBits(sample.waterSurface()) + "|"
                        + sample.waterKind() + "|" + sample.hazardous() + "|" + sample.recipe() + "|"
                        + sample.landform() + "|" + plan.solidSurfaceAt(x, z, sample)).getBytes(StandardCharsets.UTF_8));
            }
        }
        for (var structure : plan.plannedStructures())
            digest.update((structure.instanceId()+"@"+structure.anchorX()+","+structure.anchorZ())
                    .getBytes(StandardCharsets.UTF_8));
        digest.update(("spawn=" + plan.spawnPosition()).getBytes(StandardCharsets.UTF_8));
        digest.update(("fillerSeedCount=" + plan.fillerSeedCount()).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // Recorded on 001cf9f (minecraft 1.21.1 / neoforge 21.1.249 / planner-v2) before any
    // structural change. These are evidence of preserved behaviour, not tuning targets:
    // a structural refactor must reproduce them exactly. Behaviour changes require their
    // own reviewed revision, never a quiet edit of these constants.
    //
    // ============================ REVISION 2 (2026-09-11) ============================
    // EXPECTED_PLAN_SHA256 only. EXPECTED_FIELD_SHA256 and EXPECTED_SPAWN are untouched, and so are
    // PlanningOptimizationGoldenTest, DeterminismAcceptanceTest and the plan-v3 round trip.
    //
    // Old: 60db9c350e163356b58be39437bddc31e1cfde5ef9322994f2763e974421ccfe
    // New: 9c0327dea3bdc5798b6d9a21a2818417891fa7204724abb4a2593fc79f62e578
    //
    // Cause, and the only cause: "已知边界与不一致解决方案" item 22. ClimateDiagnostics.distribute
    // used to rebuild each site's temperature band from the field's raw value with hardcoded
    // 2.5/5/7.5 thresholds, while actualRatios and climateArea read ClimateField.band. The demand
    // spread therefore disagreed with every other reader of the same field, and could not have been
    // right for a field with a different threshold set. It now reads band() like everything else,
    // which changes the persisted climate statistics (ClimateState ratios/actual/supply).
    //
    // Verified scope of the change: the field digest - terrain height, water, biome and structure
    // observations sampled from the plan - is byte-identical to the recorded baseline, the
    // optimization golden values are unchanged, and repeated planning plus the READY round trip
    // still produce identical bytes. Only the diagnostic statistics moved, which is exactly what the
    // item predicted: "该统计虽不反馈布局，却会持久化，因此统计变化需单独核对规范字节，不能称为绝对
    // 字节不变." This revision was re-recorded deliberately; it is not a batch refresh of every hash.
    //
    // ============================ REVISION 3 (2026-09-20) ============================
    // EXPECTED_PLAN_SHA256 and EXPECTED_FIELD_SHA256. EXPECTED_SPAWN is untouched.
    //
    // Old plan:  0d3eac42f4da16daaa4e7a8e0bcd1e54dee67d4bf73e3b5e60e9f3a3759cf3ef
    // New plan:  b5397002aae053d06bc01c224c455891a95cbf216052777fd483be051ade0d97
    // Old field: 21a54e68a20299639024c094bfb85bd182b3a4808bf77a6fcd4e98e885044196
    // New field: d104c30bc2cf264e0f617e628d2fa76c2e19514122c719e9dbadc397ebb789e5
    //
    // The plan hash changes because plan-v3 no longer writes the unused historical random_keys
    // metadata. The field hash changes because structure carriers now seed exactly one ownership
    // cell and grow under the ordinary biome-allocation rules instead of preclaiming a fixed core;
    // structure anchors also no longer apply a fabricated 32x32 flatness check. Those are the
    // reviewed contract changes under test, while repeated planning, READY round trips and the
    // unchanged spawn signature remain independently checked above.
    private static final String EXPECTED_PLAN_SHA256 =
            "cad9e41238f4f827cb77d47038978b070d0638fe7081108484be8c2d6451794b";
    private static final String EXPECTED_FIELD_SHA256 =
            "d104c30bc2cf264e0f617e628d2fa76c2e19514122c719e9dbadc397ebb789e5";
    private static final String EXPECTED_SPAWN = "0.5/0.5/0.0";
}
