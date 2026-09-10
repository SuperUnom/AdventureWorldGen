package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.BiomeAdapter;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.StructureAdapter;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.planner.RequirementExpander;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Structure preparation is the adapter side of joint planning. These checks pin the two queries the
 * bridge owns: the spawn reservation radius coast generation must keep free, and the frozen
 * structure itself, including the failure codes a missing or rejected adapter produces.
 */
class StructureAdapterBridgeTest {
    private static final ContentId KEEP = new ContentId("test:keep");
    private static final MacroTerrain TERRAIN = (x, z) -> {
        throw new AssertionError("preparation must not sample terrain");
    };
    private static final String SPAWN_KEEP_PROFILE = """
            {"world":{"radius":512},"spawn":{"structure":{"id":"test:keep","spawn_point":[3,0,4]}},
             "biomes":{"filler":["test:plains"]},
             "structures":[{"id":"test:keep","adventure_level":4,"count":{"min":1,"max":1},
                            "allowed_biomes":{"id":["test:plains"]},"entrance":[0,0,0]}]}
            """;
    private static final String BIOME_ONLY_PROFILE = """
            {"world":{"radius":512},"spawn":{"biome":"test:plains"},
             "biomes":{"filler":["test:plains"]}}
            """;

    @Test
    void withoutASpawnStructureNothingIsReserved() {
        assertEquals(0.0, StructureAdapterBridge.spawnReservationRadius(registry(adapter(true)),
                parse(BIOME_ONLY_PROFILE)));
    }

    @Test
    void theReservationAddsTheAdapterFootprintToTheSpawnOffset() {
        double radius = StructureAdapterBridge.spawnReservationRadius(registry(adapter(true)),
                parse(SPAWN_KEEP_PROFILE));
        assertEquals(40.0 + StrictMath.hypot(3, 4), radius);
    }

    @Test
    void aSpawnStructureWithoutAnAdapterIsNamedAsUnsupported() {
        var failure = assertThrows(PlanningFailure.class,
                () -> StructureAdapterBridge.spawnReservationRadius(registry(), parse(SPAWN_KEEP_PROFILE)));
        assertEquals(PlanningFailure.Code.UNSUPPORTED_CONTENT, failure.code());
        assertEquals("spawn-reservation", failure.stage());
    }

    @Test
    void freezeKeepsTheAdapterPlacementAndTheSeedSelectedRotation() {
        var adapter = adapter(true);
        var bridge = new StructureAdapterBridge(registry(adapter), TERRAIN);

        // The adapter offers two rotations; the frozen seed decides which one, exactly as before.
        var even = bridge.freeze(demand(), 10, 70, 20, 4L);
        var odd = bridge.freeze(demand(), 10, 70, 20, 5L);

        assertEquals("north", even.rotation());
        assertEquals("east", odd.rotation());
        assertEquals(new StructureAdapter.Candidate("instance/test:keep/0", 10, 70, 20, "east"),
                adapter.lastCandidate);
        assertEquals(KEEP, even.structureId());
        assertEquals("instance/test:keep/0", even.instanceId());
        assertEquals(10, even.originX());
        assertEquals(70, even.originY());
        assertEquals(20, even.originZ());
        assertEquals(11, even.entranceX());
        assertEquals(72, even.entranceY());
        assertEquals(21, even.entranceZ());
        assertEquals(adapter.prepared.footprint(), even.footprint());
        assertEquals(adapter.prepared.biomeProtection(), even.biomeProtection());
        assertEquals(adapter.prepared.pieces(), even.pieces());
        assertSame(TERRAIN, adapter.validatedAgainst, "validation must run against the planned terrain");
    }

    @Test
    void freezeReportsAMissingAdapterAndARejectedPlacement() {
        var missing = new StructureAdapterBridge(registry(), TERRAIN);
        var unsupported = assertThrows(PlanningFailure.class, () -> missing.freeze(demand(), 0, 64, 0, 1L));
        assertEquals(PlanningFailure.Code.UNSUPPORTED_CONTENT, unsupported.code());
        assertEquals("structure-prepare", unsupported.stage());

        var rejecting = new StructureAdapterBridge(registry(adapter(false)), TERRAIN);
        var rejected = assertThrows(PlanningFailure.class, () -> rejecting.freeze(demand(), 0, 64, 0, 1L));
        assertEquals(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, rejected.code());
        assertEquals("structure-prepare", rejected.stage());
    }

    /** The demand record joint planning hands to the bridge; built the way the expander builds it. */
    private static RequirementExpander.StructureInstanceDemand demand() {
        return new RequirementExpander.StructureInstanceDemand("instance/test:keep/0", KEEP, 0, 4,
                List.of(new ContentId("test:plains")), AdventureWorldConfig.AreaRange.DEFAULT,
                new AdventureWorldConfig.Vec3d(0, 0, 0), false, true);
    }

    private static AdventureWorldConfig parse(String json) {
        return new AdventureWorldConfigParser().parse(json);
    }

    private static AdapterRegistry registry(StructureAdapter... adapters) {
        var builder = AdapterRegistry.builder(new TestBiomeAdapter());
        for (var adapter : adapters) builder.add(adapter);
        return builder.build();
    }

    private static TestStructureAdapter adapter(boolean validate) { return new TestStructureAdapter(validate); }

    private record TestBiomeAdapter() implements BiomeAdapter {
        @Override public ContentId biomeId() { return new ContentId("test:generic"); }
        @Override public String adapterVersion() { return "test-1"; }
        @Override public Compatibility compatibility(MacroSample terrain) { return new Compatibility(true, 1.0, "test"); }
        @Override public SurfacePalette surface(MacroSample terrain) {
            return new SurfacePalette(new ContentId("test:top"), new ContentId("test:under"),
                    new ContentId("test:stone"), 3);
        }
    }

    /** A structure adapter that freezes a fixed piece list and records what it was asked to do. */
    private static final class TestStructureAdapter implements StructureAdapter {
        private final boolean validate;
        private final Prepared prepared;
        private Candidate lastCandidate;
        private MacroTerrain validatedAgainst;

        private TestStructureAdapter(boolean validate) {
            this.validate = validate;
            this.prepared = new Prepared(new Candidate("instance/test:keep/0", 10, 70, 20, "north"),
                    List.of(new AdventurePlanView.PlannedPiece("instance/test:keep/0/piece/0",
                            0, 70, 10, 20, 79, 30, new byte[]{1, 2, 3})),
                    List.of(new StructureAdapter.HorizontalBox(0, 10, 20, 30)),
                    List.of(new StructureAdapter.HorizontalBox(-2, 8, 22, 32)),
                    11, 72, 21);
        }

        @Override public ContentId structureId() { return KEEP; }
        @Override public String adapterVersion() { return "test-1"; }

        @Override public Descriptor describe() {
            return new Descriptor(List.of("north", "east"), 40.0, true, false);
        }

        @Override public Prepared prepare(Candidate candidate, long structureSeed) {
            lastCandidate = candidate;
            return prepared;
        }

        @Override public List<String> validatePrepared(Prepared structure, MacroTerrain terrain) {
            validatedAgainst = terrain;
            return validate ? List.of() : List.of("test rejection");
        }
    }
}
