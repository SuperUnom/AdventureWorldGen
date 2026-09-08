package io.github.luoyan.adventureworldgen.erosion;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyProfile;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErosionGeneratorTest {
    @Test
    void producesDeterministicWorldAlignedDeltaAndKeepsQueriesInsideBoundary() {
        MacroTerrain slope = (x, z) -> new MacroSample(100 + x * 0.05 + z * 0.02,
                Double.NaN, WaterKind.NONE, false, "region/test", "hills", "terrain/test");
        var generator = new ErosionGenerator(PlannerProfile.V2, HydrologyProfile.FTF_ADAPTED_V1);
        ErosionDeltaField first = generator.generate(99, slope, -32, -32, 8, 9, 9);
        ErosionDeltaField second = generator.generate(99, slope, -32, -32, 8, 9, 9);
        assertEquals(first, second);
        assertTrue(Double.isFinite(first.sample(-1000, -1000)));
        assertTrue(Double.isFinite(first.sample(1000, 1000)));
        assertEquals(new ErodedTerrain(slope, first, "erosion/test").sample(0, 0),
                new ErodedTerrain(slope, second, "erosion/test").sample(0, 0));
        for (float delta : first.copyDeltas()) {
            assertTrue(Float.isFinite(delta));
            assertTrue(delta >= -12.0f && delta <= 8.0f);
        }
    }
}
