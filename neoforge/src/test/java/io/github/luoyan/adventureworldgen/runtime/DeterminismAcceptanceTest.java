package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.persistence.AtomicPlanRepository;
import io.github.luoyan.adventureworldgen.persistence.PlanV2Codec;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;

class DeterminismAcceptanceTest {
    @Test
    void tenIndependentPlansAndThreeRuntimeVisitOrdersAreByteStable() throws Exception {
        var config = new AdventureWorldConfigParser().parse(new StringReader("""
                {"world":{"radius":512},"spawn":{"biome":"minecraft:plains"},
                 "biomes":{"required":[{"id":"minecraft:forest","adventure_level":3,
                 "area":{"min":4096,"max":8192}}],
                 "filler":["minecraft:plains","minecraft:forest","minecraft:desert"]},"structures":[]}
                """));
        List<Vec2> vertices = List.of(new Vec2(-512, -512), new Vec2(512, -512),
                new Vec2(512, 512), new Vec2(-512, 512));
        var coast = new Coastline(vertices);
        var rivers = new RiverNetwork(List.of(), List.of(), PlannerProfile.V2.hydrologyVersion());
        var patches = List.of(new PlannedBiomePatch(
                "patch/required/0", new ContentId("minecraft:forest"), 3, 96, -32, 160, 32),
                new PlannedBiomePatch(
                        "patch/spawn", new ContentId("minecraft:plains"), 0, -128, -128, 128, 128));
        String expectedPlanHash = null;
        for (int run = 0; run < 10; run++) {
            var plan = new GeneratedAdventurePlan(0x5EEDL, config, coast, rivers, 64, 64, 128,
                    "terrain-v2", null, patches, List.of(),
                    GeneratedAdventurePlan.PlanDiagnostics.basic(coast, rivers), null);
            byte[] encoded = new PlanV2Codec().encode(new ContentId("adventureworldgen:default"), "same-input", plan);
            String planHash = AtomicPlanRepository.sha256(encoded);
            if (expectedPlanHash == null) expectedPlanHash = planHash;
            assertEquals(expectedPlanHash, planHash);

            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < 1024; i++) order.add(i);
            String forward = runtimeSignature(plan, order);
            Collections.reverse(order);
            String reverse = runtimeSignature(plan, order);
            Collections.shuffle(order, new Random(90125));
            String shuffled = runtimeSignature(plan, order);
            assertEquals(forward, reverse);
            assertEquals(forward, shuffled);
        }
    }

    private static String runtimeSignature(GeneratedAdventurePlan plan, List<Integer> order) throws Exception {
        List<String> samples = new ArrayList<>();
        for (int index : order) {
            int x = (index % 32 - 16) * 17;
            int z = (index / 32 - 16) * 17;
            var terrain = plan.terrainAt(x + 0.5, z + 0.5);
            samples.add(index + ":" + plan.biomeAt(x, 64, z) + ":"
                    + Double.doubleToRawLongBits(terrain.groundSurface()) + ":"
                    + Double.doubleToRawLongBits(terrain.waterSurface()) + ":" + terrain.waterKind());
        }
        samples.sort(String::compareTo);
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            for (String sample : samples) out.writeUTF(sample);
            out.flush();
            return AtomicPlanRepository.sha256(bytes.toByteArray());
        }
    }
}
