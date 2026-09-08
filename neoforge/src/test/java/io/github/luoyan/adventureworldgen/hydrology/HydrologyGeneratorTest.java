package io.github.luoyan.adventureworldgen.hydrology;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import io.github.luoyan.adventureworldgen.terrain.CoastGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HydrologyGeneratorTest {
    @Test
    void fixedCommitRetainedSettingsMatchGoldenVector() {
        var value = HydrologyProfile.FTF_ADAPTED_V1;
        assertEquals(8, value.mainRiverCount());
        assertEquals(3, value.maximumForkDepth());
        assertEquals(new HydrologyProfile.RiverShape(5, 2, 6, 20, 8, 0.75), value.main());
        assertEquals(new HydrologyProfile.RiverShape(4, 1, 4, 14, 5, 0.975), value.branch());
        assertEquals(new HydrologyProfile.Lake(0.3, 0.0, 0.03, 10, 75, 150, 2, 10), value.lake());
        assertEquals(new HydrologyProfile.Wetland(0.6, 175, 225), value.wetland());
        assertEquals(new HydrologyProfile.Erosion(135, 12, 0.7, 0.7, 0.5, 0.5), value.erosion());
        assertEquals(new HydrologyProfile.Smoothing(1, 1.8, 0.9), value.smoothing());
    }

    @Test
    void generatesEightMainsWithCoastalNonRisingMouthsDeterministically() {
        // Fix the coast in this river-only fixture so added coastal detail cannot change
        // which particular tributary candidates are feasible for the assertion below.
        var coast = new io.github.luoyan.adventureworldgen.terrain.Coastline(
                java.util.stream.IntStream.range(0, 64).mapToObj(i ->
                        new io.github.luoyan.adventureworldgen.spatial.Vec2(
                                6000 * Math.cos(i * Math.PI / 32), 6000 * Math.sin(i * Math.PI / 32))).toList());
        // A flat interior guarantees room for tributaries; coastline detail alone must not
        // make this hydrology fixture depend on accidental low surrounding hills.
        MacroTerrain base = (x, z) -> new MacroSample(120,
                Double.NaN, WaterKind.NONE, false, "region/test", "plains", "terrain/test");
        var generator = new HydrologyGenerator(PlannerProfile.V2, HydrologyProfile.FTF_ADAPTED_V1);
        RiverNetwork first = generator.generate(7331, 6000, 64, coast, base);
        RiverNetwork second = generator.generate(7331, 6000, 64, coast, base);
        assertEquals(first, second);
        assertEquals(8, first.channels().stream().filter(channel -> channel.order() == 0).count());
        assertTrue(first.channels().stream().anyMatch(channel -> channel.order() > 0));
        for (var channel : first.channels()) {
            assertTrue(channel.order() <= 3);
            for (int i = 1; i < channel.waterSurfaces().size(); i++)
                assertTrue(channel.waterSurfaces().get(i) <= channel.waterSurfaces().get(i - 1) + 1e-9);
            if (channel.order() == 0) {
                var mouth = channel.points().getLast();
                assertEquals(0, coast.signedDistance(mouth.x(), mouth.z()), 1e-6);
                assertEquals(64, channel.waterSurfaces().getLast(), 1e-9);
            }
        }
    }

    @Test
    void carvesBedAtLeastTwoBlocksBelowWater() {
        var coast = new CoastGenerator(PlannerProfile.V2).generate(7331, 6000, 300).coastline();
        MacroTerrain base = (x, z) -> new MacroSample(120, Double.NaN, WaterKind.NONE, false,
                "region/test", "plains", "terrain/test");
        RiverNetwork network = new HydrologyGenerator(PlannerProfile.V2, HydrologyProfile.FTF_ADAPTED_V1)
                .generate(7331, 6000, 64, coast, base);
        var first = network.channels().getFirst();
        var center = HydrologyGenerator.pointAt(first, 0.5);
        MacroSample carved = new HydrologyTerrain(base, network).sample(center.x(), center.z());
        assertTrue(carved.waterKind() == WaterKind.RIVER || carved.waterKind() == WaterKind.LAKE);
        assertTrue(carved.waterSurface() - carved.groundSurface() >= 2.0);
    }
}
