package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.api.AdapterRegistry;
import io.github.luoyan.adventureworldgen.api.BiomeAdapter;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.config.LoadedProfile;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The input fingerprint decides whether a stored READY plan is reused. These checks pin which
 * inputs must move it: an input missing here would silently reuse a plan built for other inputs.
 */
class PlanIdentityTest {
    private static final String CONFIG = """
            {"world":{"radius":512},"spawn":{"biome":"test:plains"},
             "biomes":{"required":[],"filler":["test:plains"]},"structures":[]}
            """;

    @Test
    void everyDocumentedInputChangesTheIdentity() {
        var loaded = profile("{\"canonical\":1}");
        var adapters = registry("v1");

        String baseline = PlanIdentity.hash(7L, loaded, adapters);

        assertEquals(baseline, PlanIdentity.hash(7L, loaded, adapters), "identical inputs must be identical");
        assertTrue(baseline.matches("[0-9a-f]{64}"), "identity is a sha-256 hex digest");
        assertNotEquals(baseline, PlanIdentity.hash(8L, loaded, adapters), "the seed is part of the identity");
        assertNotEquals(baseline, PlanIdentity.hash(7L, profile("{\"canonical\":2}"), adapters),
                "the canonical author profile is part of the identity");
        assertNotEquals(baseline, PlanIdentity.hash(7L, loaded, registry("v2")),
                "registered adapter versions are part of the identity");
        assertNotEquals(baseline, PlanIdentity.hash(7L, loaded, registry("v1", "test:extra")),
                "a newly registered content adapter is part of the identity");
    }

    private static LoadedProfile profile(String canonicalJson) {
        return new LoadedProfile(new ContentId("adventureworldgen:identity-test"),
                new AdventureWorldConfigParser().parse(CONFIG), canonicalJson, "config-hash", "test-pack");
    }

    private static AdapterRegistry registry(String version, String... extraBiomes) {
        var builder = AdapterRegistry.builder(new TestBiomeAdapter(new ContentId("test:generic"), version));
        for (String biome : extraBiomes) builder.add(new TestBiomeAdapter(new ContentId(biome), version));
        return builder.build();
    }

    private record TestBiomeAdapter(ContentId biomeId, String adapterVersion) implements BiomeAdapter {
        @Override public Compatibility compatibility(MacroSample terrain) {
            return new Compatibility(true, 1.0, "test");
        }

        @Override public SurfacePalette surface(MacroSample terrain) {
            return new SurfacePalette(new ContentId("test:top"), new ContentId("test:under"),
                    new ContentId("test:stone"), 3);
        }
    }
}
