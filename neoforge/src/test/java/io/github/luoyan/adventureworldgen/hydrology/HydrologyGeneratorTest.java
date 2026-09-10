package io.github.luoyan.adventureworldgen.hydrology;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.terrain.CoastGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HydrologyGeneratorTest {
    @Test void smallContinentsStillFitIndependentCatchments() throws java.io.IOException {
        io.github.luoyan.adventureworldgen.config.AdventureWorldConfig config;
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(
                "src/testmod/resources/data/adventureworldgen/adventureworldgen/profiles/default.json"))) {
            config = new io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser().parse(reader);
        }
        for (long seed : new long[]{72362148366599L, 1, 7331}) {
            var coast = new CoastGenerator(PlannerProfile.V2).generate(seed, 1536, 185.6);
            var capacities = io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan.reserve(seed, config, coast.coastline(), coast.landBand());
            var island = new io.github.luoyan.adventureworldgen.terrain.IslandMacroTerrain(coast.coastline(),
                    new io.github.luoyan.adventureworldgen.terrain.RegionTerrain(seed, PlannerProfile.V2, capacities),
                    seed, 64, coast.landBand(), coast.seaBand(), "test");
            var erosion = new io.github.luoyan.adventureworldgen.erosion.ErosionGenerator(PlannerProfile.V2, HydrologyProfile.FINITE_CONTINENT)
                    .generate(seed, island, -1792, -1792, 8, 449, 449);
            var base = new io.github.luoyan.adventureworldgen.erosion.ErodedTerrain(island, erosion, "test");
            var network = new HydrologyGenerator(PlannerProfile.V2, HydrologyProfile.FINITE_CONTINENT)
                    .generate(seed, 1536, 64, coast.coastline(), base);
            assertEquals(HydrologyProfile.FINITE_CONTINENT.mainRiverCount(1536), network.channels().stream().filter(c -> c.parentId() == null).count());
            assertTrue(network.channels().stream().allMatch(c -> c.order() <= 1));
            assertTrue(network.channels().size() <= 24, "too many short tributaries");
        }
    }

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
        var mains = first.channels().stream().filter(channel -> channel.order() == 0).toList();
        assertTrue(mains.stream().mapToInt(c -> c.shape().bedWidth()).max().orElseThrow()
                >= 2 * mains.stream().mapToInt(c -> c.shape().bedWidth()).min().orElseThrow(), "trunks have uniform widths");
        double minimumSinuosity = Double.POSITIVE_INFINITY, maximumSinuosity = 0, totalSinuosity = 0;
        int curved = 0;
        for (var channel : mains) {
            var source = channel.points().getFirst();
            var mouth = channel.points().getLast();
            double chord = source.distance(mouth);
            double sinuosity = channel.length() / chord;
            minimumSinuosity = Math.min(minimumSinuosity, sinuosity);
            maximumSinuosity = Math.max(maximumSinuosity, sinuosity); totalSinuosity += sinuosity;
            double oblique = Math.abs(source.x() * (mouth.z() - source.z()) - source.z() * (mouth.x() - source.x()))
                    / (Math.hypot(source.x(), source.z()) * chord);
            assertTrue(oblique > 0.45, "river still points radially from the center");
            int left = 0, right = 0;
            for (int i = 4; i < channel.points().size() - 4; i += 4) {
                var a = channel.points().get(i - 4); var b = channel.points().get(i); var c = channel.points().get(i + 4);
                double turn = (b.x() - a.x()) * (c.z() - b.z()) - (b.z() - a.z()) * (c.x() - b.x());
                if (turn > 5) left++; if (turn < -5) right++;
            }
            if (left >= 3 || right >= 3) curved++;
        }
        assertTrue(curved >= 6 && totalSinuosity / mains.size() > 1.08, "network lacks developed bends");
        assertTrue(maximumSinuosity - minimumSinuosity > 0.08, "river shapes are too uniform");
        assertTrue(first.channels().stream().filter(c -> c.order() > 0).count() >= 2, "no tributary catchments survived");
        for (var parent : first.channels())
            assertTrue(first.channels().stream().filter(c -> parent.id().equals(c.parentId())).count() <= 3,
                    "tributary joins are too dense");
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

    @Test void finiteContinentPrefersLongWideTrunksWithSparseTributaries() {
        var coast = new io.github.luoyan.adventureworldgen.terrain.Coastline(
                java.util.stream.IntStream.range(0, 64).mapToObj(i ->
                        new io.github.luoyan.adventureworldgen.spatial.Vec2(
                                3000 * Math.cos(i * Math.PI / 32), 3000 * Math.sin(i * Math.PI / 32))).toList());
        MacroTerrain base = (x, z) -> new MacroSample(120, Double.NaN, WaterKind.NONE,
                false, "test", "plains", "test");
        var network = new HydrologyGenerator(PlannerProfile.V2, HydrologyProfile.FINITE_CONTINENT)
                .generate(7331, 3000, 64, coast, base);
        var morphology = new RiverMorphology(network);
        var mains = network.channels().stream().filter(c -> c.order() == 0).toList();
        assertEquals(6, mains.size());
        assertTrue(mains.stream().mapToDouble(RiverNetwork.Channel::length).average().orElseThrow() > 1440,
                "main catchments remain too short");
        for (var channel : mains) {
            var p = HydrologyGenerator.pointAt(channel, .5);
            assertTrue(morphology.bedRadius(channel,.5,p.x(),p.z(),p.x(),p.z()) * 2 >= 16,
                    "middle reach is still a narrow stream");
        }
        assertTrue(network.channels().stream().allMatch(c -> c.order() <= 1));
        assertTrue(network.channels().size() <= 24, "tributaries overwhelm the six trunks");
        assertTrue(network.channels().size() > 6, "all tributaries disappeared");
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
