package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.StructureAdapter;
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

/**
 * P0 reproducibility baseline for the structural refactor.
 *
 * <p>This drives the same stage order as {@link RuntimePlanner} without any Minecraft
 * registry, adapter or client dependency, then pins everything the refactor must not
 * change: the canonical plan-v2 bytes, the READY round trip, and sampled terrain /
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
                            "allowed_biomes":{"id":["test:desert"],"area":{"min":4096,"target":8192}},
                            "entrance":[0,0,0]}]}
            """;

    @Test
    void canonicalPlanBytesRoundTripAndSampledFieldsMatchTheRecordedBaseline() throws Exception {
        var config = new AdventureWorldConfigParser().parse(CONFIG);
        var codec = new PlanV2Codec();
        var plan = buildPlan(config);

        byte[] encoded = codec.encode(PROFILE, INPUT_HASH, plan.snapshot());
        String planHash = sha256(encoded);

        // READY reload must reproduce the same canonical bytes: restore never re-plans.
        GeneratedAdventurePlan reloaded = GeneratedAdventurePlan.restore(config,
                codec.decode(encoded, PROFILE, INPUT_HASH));
        byte[] reencoded = codec.encode(PROFILE, INPUT_HASH, reloaded.snapshot());
        assertArrayEquals(encoded, reencoded, "READY reload changed the canonical plan bytes");

        assertEquals(List.of(EXPECTED_PLAN_SHA256, EXPECTED_FIELD_SHA256, EXPECTED_SPAWN),
                List.of(planHash, sampleFields(plan), spawnSignature(plan)),
                "recorded planning baseline changed");
    }

    /** Mirrors RuntimePlanner's stage order and object reuse without its Minecraft adapters. */
    private static GeneratedAdventurePlan buildPlan(AdventureWorldConfig config) {
        double radius = config.world().radius();
        double spawnRadius = StrictMath.min(256.0, radius / 10.0);
        double keep = spawnRadius + StrictMath.min(32.0, radius / 20.0);

        var coast = new CoastGenerator(PlannerProfile.V2).generate(SEED, radius, keep);
        var capacities = TerrainCapacitySolver.reserve(SEED, config, coast.coastline(), coast.landBand());
        var regions = new RegionTerrain(SEED, PlannerProfile.V2, capacities, config.world().terrain(), config);
        var island = new IslandMacroTerrain(coast.coastline(), regions, SEED, 64.0,
                coast.landBand(), coast.seaBand(), "terrain-r22");

        int erosionSpacing = 8;
        int erosionExtent = (int) StrictMath.ceil((radius + 256.0) / erosionSpacing) * erosionSpacing;
        int erosionSize = erosionExtent * 2 / erosionSpacing + 1;
        ErosionDeltaField erosion = new ErosionGenerator(PlannerProfile.V2, HydrologyProfile.FINITE_CONTINENT)
                .generate(SEED, island, -erosionExtent, -erosionExtent, erosionSpacing, erosionSize, erosionSize);
        var erodedIsland = new ErodedTerrain(island, erosion, "erosion-v2");
        var rivers = new HydrologyGenerator(PlannerProfile.V2, HydrologyProfile.FINITE_CONTINENT)
                .generate(SEED, radius, 64.0, coast.coastline(), erodedIsland);
        var waterTerrain = new HydrologyTerrain(erodedIsland, rivers);
        MacroTerrain erodedTerrain = new ExactGridTerrain(new TerrainMorphology(waterTerrain), 262144);
        var costs = new CostPlanner(PlannerProfile.V2).build(erodedTerrain, coast.coastline(), new Vec2(0.5, 0.5));

        var jointPlanner = new JointPlanner(PlannerProfile.V2);
        var joint = jointPlanner.plan(SEED, config, erodedTerrain, recorderFreezer(),
                new JointPlanner.LevelConstraint() {
                    public boolean accepts(int level, int x, int z) { return true; }
                    public double penalty(int level, int x, int z) {
                        return io.github.luoyan.adventureworldgen.cost.AdventurePreference.penalty(level,
                                costs.normalizedPreferenceAt(x, z, radius));
                    }
                }, (biome, x, z) -> true, ignored -> { }, ignored -> { });

        return new GeneratedAdventurePlan(SEED, config, coast.coastline(), rivers, 64.0,
                coast.landBand(), coast.seaBand(), "terrain-r22", joint.spawn(),
                joint.patches(), joint.structures(),
                new PlanDiagnostics(coast.vertexCount(), rivers.channels().size(),
                        rivers.channels().stream().mapToLong(channel -> channel.points().size()).sum(),
                        (long) erosion.width() * erosion.height(), erosion.operationCount(),
                        costs.nodeCount(), costs.edgeStats().computations(), joint.operationCount(),
                        "terrain-r22+" + PlannerProfile.V2.hydrologyVersion() + "+erosion-v2"),
                erosion, capacities, null,
                new GeneratedAdventurePlan.PlanningInputs(regions, island, waterTerrain, erodedTerrain,
                        jointPlanner.climate()));
    }

    /**
     * A frozen structure with two real pieces but an origin-sized reservation, so the
     * baseline exercises piece NBT and rotation without depending on carrier geometry.
     */
    private static JointPlanner.StructureFreezer recorderFreezer() {
        return (demand, x, y, z, structureSeed) -> {
            String prefix = demand.instanceId() + "/piece/";
            var pieces = List.of(
                    new AdventurePlanView.PlannedPiece(prefix + "0", x - 12, y, z - 12, x + 12, y + 9, z + 12,
                            ("baseline-nbt-0:" + demand.instanceId() + ":" + structureSeed)
                                    .getBytes(StandardCharsets.UTF_8)),
                    new AdventurePlanView.PlannedPiece(prefix + "1", x + 4, y, z - 4, x + 20, y + 5, z + 4,
                            ("baseline-nbt-1:" + demand.instanceId()).getBytes(StandardCharsets.UTF_8)));
            var reservation = List.of(new StructureAdapter.HorizontalBox(x, z, x, z));
            return new AdventurePlanView.PlannedStructure(demand.instanceId(), demand.structureId(),
                    x, y, z, "north", x, y, z, reservation, reservation, pieces);
        };
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
        for (int chunkX = -34; chunkX <= 34; chunkX += 8) {
            for (int chunkZ = -34; chunkZ <= 34; chunkZ += 8) {
                var found = plan.structuresIntersecting(chunkX, chunkZ);
                StringBuilder line = new StringBuilder(chunkX + "/" + chunkZ + "=");
                for (var structure : found) line.append(structure.instanceId()).append('@')
                        .append(structure.originX()).append(',').append(structure.originY()).append(',')
                        .append(structure.originZ()).append(';').append(structure.rotation()).append(';')
                        .append(structure.entranceX()).append(',').append(structure.entranceZ()).append(',')
                        .append(structure.pieces().size()).append(' ');
                digest.update(line.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
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
    private static final String EXPECTED_PLAN_SHA256 =
            "60db9c350e163356b58be39437bddc31e1cfde5ef9322994f2763e974421ccfe";
    private static final String EXPECTED_FIELD_SHA256 =
            "ce1ac745adc29f185a2867031eb7a8b29de29f895066226014e1211da0a92a0f";
    private static final String EXPECTED_SPAWN = "0.5/0.5/0.0";
}
