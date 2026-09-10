package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class VanillaRiverBiomeAdapterTest {
    @Test void sedimentsFormDeterministicPatchesInBothRiverBiomes() {
        var generic = new io.github.luoyan.adventureworldgen.worldgen.GenericBiomeAdapter();
        for (String biome : new String[]{"minecraft:river", "minecraft:frozen_river"}) {
            var adapter = new VanillaRiverBiomeAdapter(new ContentId(biome));
            Set<String> materials = new HashSet<>();
            int same = 0, pairs = 0;
            for (double depth : new double[]{2, 7}) {
                var sample = new MacroSample(100 - depth, 100, WaterKind.RIVER, false, "test", "plains", "test");
                for (int x = -96; x < 96; x++) for (int z = -96; z < 96; z++) {
                    var palette = adapter.surface(sample, 7331, x, z);
                    materials.add(palette.top().value());
                    assertEquals(palette, generic.surface(sample, 7331, x, z), "generic biome fallback did not use river sediments");
                    if (palette.equals(adapter.surface(sample, 7331, x + 1, z))) same++;
                    pairs++;
                }
                var expected = adapter.surface(sample, 7331, -16, 256);
                adapter.surface(sample, 999, -16, 256);
                assertEquals(expected, adapter.surface(sample, 7331, -16, 256), "world seed cache changed output");
            }
            assertEquals(Set.of("minecraft:dirt", "minecraft:gravel", "minecraft:sand", "minecraft:clay", "minecraft:stone"), materials);
            assertTrue(same > pairs * 0.85, "sediments are speckled instead of forming deposits");
            var dry = new MacroSample(102, Double.NaN, WaterKind.NONE, false, "test", "plains", "test");
            assertEquals("minecraft:grass_block", adapter.surface(dry, 7331, 0, 0).top().value());
        }
    }
}
