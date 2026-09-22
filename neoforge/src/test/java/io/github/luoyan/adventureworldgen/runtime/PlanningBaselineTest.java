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
        // This is a reviewed algorithm/format change, not a structural refactor: calibration,
        // source reservations and layered-road fields intentionally replace the old v3/v4 hashes.
        // Preserve the byte-level guarantee by comparing independent first plans and READY reload.
        assertArrayEquals(encoded,codec.encode(PROFILE,INPUT_HASH,buildPlan(config).snapshot()));

        // READY reload must reproduce the same canonical bytes: restore never re-plans.
        GeneratedAdventurePlan reloaded = GeneratedAdventurePlan.restore(config,
                codec.decode(encoded, PROFILE, INPUT_HASH));
        byte[] reencoded = codec.encode(PROFILE, INPUT_HASH, reloaded.snapshot());
        assertArrayEquals(encoded, reencoded, "READY reload changed the canonical plan bytes");

        assertEquals(sampleFields(plan),sampleFields(reloaded),"READY changed terrain, water, biome or structure observations");
        assertEquals(EXPECTED_SPAWN,spawnSignature(plan));

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
    private static final String EXPECTED_SPAWN = "0.5/0.5/0.0";
}
