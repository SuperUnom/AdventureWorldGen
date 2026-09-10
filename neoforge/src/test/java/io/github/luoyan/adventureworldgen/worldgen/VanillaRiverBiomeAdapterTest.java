package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VanillaRiverBiomeAdapterTest {
    @Test void retainsPlanningPreference() {
        var generic = new GenericBiomeAdapter();
        for (String biome : new String[]{"minecraft:river", "minecraft:frozen_river"}) {
            var adapter = new VanillaRiverBiomeAdapter(new ContentId(biome));
            assertEquals(new ContentId(biome), adapter.biomeId());
            for (var kind : WaterKind.values()) for (boolean hazardous : new boolean[]{false, true}) {
                var sample = new MacroSample(98, kind == WaterKind.NONE ? Double.NaN : 100,
                        kind, hazardous, "test", "plains", "test");
                boolean allowed = !hazardous && kind != WaterKind.LAVA;
                assertEquals(allowed, adapter.compatibility(sample).allowed());
                assertEquals(allowed, generic.compatibility(sample).allowed());
                assertEquals(allowed ? 0.5 : 0.0, adapter.compatibility(sample).softPreference());
                assertEquals(0.0, generic.compatibility(sample).softPreference());
            }
        }
    }
}
