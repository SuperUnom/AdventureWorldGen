package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.api.BiomeAdapter;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.plan.ContentId;

/** External biome adapter used only by the isolated test companion source set. */
public final class TestCompanionAdapters {
    public static final ContentId ASHEN_GROVE_ID = new ContentId("testcompanion:ashen_grove");

    public static final BiomeAdapter ASHEN_GROVE = new BiomeAdapter() {
        @Override public ContentId biomeId() { return ASHEN_GROVE_ID; }
        @Override public String adapterVersion() { return "testcompanion-ashen-grove-v1"; }
        @Override public Compatibility compatibility(MacroSample terrain) {
            boolean allowed = terrain.waterKind() == WaterKind.NONE && !terrain.hazardous();
            return new Compatibility(allowed, allowed ? 0.75 : 0.0,
                    allowed ? "dry custom biome" : "requires dry terrain");
        }
    };

    private TestCompanionAdapters() {}
}
