package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.erosion.*;
import io.github.luoyan.adventureworldgen.hydrology.*;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TerrainRegressionTest {
    @Test void coastHasBaysAndSmallCovesAcrossSeeds() {
        for (long seed : new long[]{9, 7331, 8844}) {
            var coast = new CoastGenerator(PlannerProfile.V2).generate(seed, 3000, 300).coastline();
            double min = Double.POSITIVE_INFINITY, max = 0;
            int extrema = 0;
            var points = coast.vertices();
            for (int i = 0; i < points.size(); i++) {
                double r = radius(points.get(i));
                min = Math.min(min, r); max = Math.max(max, r);
                double before = radius(points.get(Math.floorMod(i - 1, points.size())));
                double after = radius(points.get((i + 1) % points.size()));
                if ((r - before) * (after - r) < 0) extrema++;
            }
            assertTrue(max - min > 550, "continent still resembles a circle: " + seed);
            assertTrue(extrema >= 6, "coast lacks local coves: " + extrema);
            assertTrue(max <= 3000.000001 && min > 300);
        }
    }

    @Test void biomePatchHasCurvedEdgesAndCountsItsActualCells() {
        var patch = new PlannedBiomePatch("spawn", new ContentId("minecraft:plains"), 0, -128, -128, 128, 128);
        assertTrue(patch.contains(0, 0));
        assertFalse(patch.contains(-128, -128));
        java.util.Set<Integer> edge = new java.util.HashSet<>();
        long cells = 0;
        for (int x = -128; x < 128; x += 4) {
            for (int z = -128; z < 128; z += 4) if (patch.contains(x + 2, z + 2)) cells++;
            for (int z = -128; z < 128; z++) if (patch.contains(x, z)) { edge.add(z); break; }
        }
        assertTrue(edge.size() > 20);
        assertEquals(cells * 16, patch.area());
    }

    @Test void riverCrossSectionIsContinuousAndShallowEdgesAreFilled() {
        MacroTerrain land = (x, z) -> sample(100, Double.NaN, WaterKind.NONE);
        var shape = HydrologyProfile.FTF_ADAPTED_V1.main();
        var channel = new RiverNetwork.Channel("river", 0, null,
                List.of(new Vec2(-512, 0), new Vec2(512, 0)), List.of(0.0, 1024.0),
                List.of(90.0, 90.0), shape, null);
        var terrain = new HydrologyTerrain(land, new RiverNetwork(List.of(channel), List.of(), "test"));
        double previous = terrain.sample(0, 0).groundSurface();
        for (double z = 0.05; z < 150; z += 0.05) {
            var now = terrain.sample(0, z);
            assertTrue(Math.abs(now.groundSurface() - previous) < 0.1, "bank discontinuity at " + z);
            assertTrue(now.groundSurface() >= previous - 1e-9);
            if (now.groundSurface() < 90) assertTrue(now.wet(), "dry hole in shallow river at " + z);
            assertTrue(now.groundSurface() <= 100);
            previous = now.groundSurface();
        }
    }

    @Test void descendingRiverUsesLocalCrossSectionAndRetainsBothBanks() {
        MacroTerrain slope = (x, z) -> sample(120 - x * 0.05, Double.NaN, WaterKind.NONE);
        var points = new java.util.ArrayList<Vec2>();
        var lengths = new java.util.ArrayList<Double>();
        var water = new java.util.ArrayList<Double>();
        for (int x = 0; x <= 512; x += 8) {
            points.add(new Vec2(x, 0)); lengths.add((double) x); water.add(110 - x * 0.05);
        }
        var channel = new RiverNetwork.Channel("descending", 0, null, points, lengths, water,
                HydrologyProfile.FTF_ADAPTED_V1.main(), null);
        var terrain = new HydrologyTerrain(slope, new RiverNetwork(List.of(channel), List.of(), "test"));
        for (int x = 32; x < 480; x++) {
            var center = terrain.sample(x + 0.5, 0.5);
            assertEquals(110 - (x + 0.5) * 0.05, center.waterSurface(), 1e-9,
                    "downstream segment overwrote local water at " + x);
            for (int side : new int[]{-1, 1}) {
                var bank = terrain.sample(x + 0.5, side * 14.5);
                assertFalse(bank.wet(), "submerged river bank");
                assertTrue(bank.groundSurface() >= center.waterSurface() + 1,
                        "descending channel has no retaining bank at " + x);
            }
        }
    }

    @Test void bankVoxelsContainDescendingWaterAtFractionalHeights() {
        MacroTerrain base = (x, z) -> sample(120, Double.NaN, WaterKind.NONE);
        var channel = new RiverNetwork.Channel("raster", 0, null,
                List.of(new Vec2(-128, -32), new Vec2(128, 32)), List.of(0.0, Math.hypot(256, 64)),
                List.of(105.4, 92.2), HydrologyProfile.FINITE_CONTINENT.main(), null);
        var terrain = new HydrologyTerrain(base, new RiverNetwork(List.of(channel), List.of(), "test"));
        int retainingEdges = 0;
        for (int x = -110; x < 110; x++) for (int z = -40; z <= 40; z++) {
            var current = terrain.sample(x + 0.5, z + 0.5);
            if (!current.wet() || Math.floor(current.waterSurface()) <= Math.floor(current.groundSurface())) continue;
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                var bank = terrain.sample(x + step[0] + 0.5, z + step[1] + 0.5);
                if (bank.wet() && Math.floor(bank.waterSurface()) > Math.floor(bank.groundSurface())) continue;
                int solid = terrain.solidSurfaceAt(x + step[0], z + step[1], bank);
                assertTrue(solid >= Math.floor(current.waterSurface()), "uncontained water beside dry bank");
                assertTrue(solid <= Math.floor(bank.groundSurface()) + 1, "bank repair built an artificial dam");
                retainingEdges++;
            }
        }
        assertTrue(retainingEdges > 100);
    }

    @Test void slopingRiverCannotBeOverlaidByAFixedWetlandWaterWall() {
        MacroTerrain slope = (x, z) -> sample(120 - x * 0.05, Double.NaN, WaterKind.NONE);
        var channel = new RiverNetwork.Channel("slope", 0, null,
                List.of(new Vec2(-256, 0), new Vec2(256, 0)), List.of(0.0, 512.0),
                List.of(112.8, 87.2), HydrologyProfile.FTF_ADAPTED_V1.main(), null);
        var wetland = new RiverNetwork.Wetland("wetland/slope", new Vec2(-100, 0), new Vec2(100, 0), 100, 95);
        var terrain = new HydrologyTerrain(slope, new RiverNetwork(List.of(channel), List.of(wetland), "test"));
        for (int x = -180; x < 180; x++) for (int z = -80; z < 80; z++) {
            var a = terrain.sample(x + 0.5, z + 0.5);
            for (var b : List.of(terrain.sample(x + 1.5, z + 0.5), terrain.sample(x + 0.5, z + 1.5))) {
                if (a.wet() && b.wet()) assertTrue(Math.abs(a.waterSurface() - b.waterSurface()) <= 0.1,
                        "adjacent water levels form a wall at " + x + "," + z);
                if (a.wet() && !b.wet()) assertTrue(b.groundSurface() >= a.waterSurface() - 0.1,
                        "water is above adjacent dry land at " + x + "," + z);
            }
        }
    }

    @Test void lakeFadeRemainsContinuousAcrossSpatialBucketBoundary() {
        MacroTerrain land = (x, z) -> sample(120, Double.NaN, WaterKind.NONE);
        var channel = new RiverNetwork.Channel("lake", 0, null,
                List.of(new Vec2(-500, 70), new Vec2(500, 70)), List.of(0.0, 1000.0),
                List.of(90.0, 90.0), HydrologyProfile.FTF_ADAPTED_V1.main(),
                new RiverNetwork.LakeWidening(0.5, 150, 10));
        var terrain = new HydrologyTerrain(land, new RiverNetwork(List.of(channel), List.of(), "test"));
        assertEquals(terrain.sample(0, 255.999).groundSurface(), terrain.sample(0, 256.001).groundSurface(), 0.01);
    }

    @Test void riverMouthCutsThroughTheShorelineWithoutAnUnderwaterWall() {
        var coast = new CoastGenerator(PlannerProfile.V2).generate(7331, 3000, 300);
        var island = new IslandMacroTerrain(coast.coastline(), new RegionTerrain(7331, PlannerProfile.V2),
                7331, 64, coast.landBand(), coast.seaBand(), "test");
        var network = new HydrologyGenerator(PlannerProfile.V2, HydrologyProfile.FTF_ADAPTED_V1)
                .generate(7331, 3000, 64, coast.coastline(), island);
        var terrain = new HydrologyTerrain(island, network);
        for (var channel : network.channels()) if (channel.parentId() == null) {
            Vec2 mouth = channel.points().getLast(), before = channel.points().get(channel.points().size() - 2);
            double dx = mouth.x() - before.x(), dz = mouth.z() - before.z(), length = Math.hypot(dx, dz);
            var land = terrain.sample(mouth.x() - dx / length * 0.05, mouth.z() - dz / length * 0.05);
            var ocean = terrain.sample(mouth.x() + dx / length * 0.05, mouth.z() + dz / length * 0.05);
            assertEquals(WaterKind.OCEAN, ocean.waterKind());
            assertEquals(64, ocean.waterSurface());
            assertTrue(ocean.waterDepth() >= 2);
            assertEquals(land.groundSurface(), ocean.groundSurface(), 0.1);
        }
    }

    @Test void lakeShoreIsAsymmetricInsteadOfARadialWidening() {
        MacroTerrain land = (x, z) -> sample(120, Double.NaN, WaterKind.NONE);
        var channel = new RiverNetwork.Channel("lake-shape", 0, null,
                List.of(new Vec2(-500, 0), new Vec2(500, 0)), List.of(0.0, 1000.0),
                List.of(90.0, 90.0), HydrologyProfile.FTF_ADAPTED_V1.main(),
                new RiverNetwork.LakeWidening(0.5, 100, 10));
        var terrain = new HydrologyTerrain(land, new RiverNetwork(List.of(channel), List.of(), "test"));
        double asymmetry = 0;
        for (int i = 2; i < 22; i++) {
            double angle = i * StrictMath.PI / 24;
            double[] shore = new double[2];
            for (int side = 0; side < 2; side++) {
                double a = angle + side * StrictMath.PI;
                for (int r = 1; r < 400; r++) {
                    if (terrain.sample(StrictMath.cos(a) * r, StrictMath.sin(a) * r).waterKind() != WaterKind.LAKE) {
                        shore[side] = r; break;
                    }
                }
            }
            asymmetry = StrictMath.max(asymmetry, StrictMath.abs(shore[0] - shore[1]));
        }
        assertTrue(asymmetry > 20, "lake still has a symmetric circular/elliptical shoreline");
    }

    @Test void wetlandDoesNotDropAbruptlyWhereOriginalGroundMeetsWaterLevel() {
        MacroTerrain land = (x, z) -> sample(90 + x * 0.2, Double.NaN, WaterKind.NONE);
        var wetland = new RiverNetwork.Wetland("shore", new Vec2(-20, 0), new Vec2(20, 0), 100, 90);
        var terrain = new HydrologyTerrain(land, new RiverNetwork(List.of(), List.of(wetland), "test"));
        double previous = terrain.sample(-1, 0).groundSurface();
        for (double x = -0.95; x < 10; x += 0.05) {
            double height = terrain.sample(x, 0).groundSurface();
            assertTrue(StrictMath.abs(height - previous) < 0.1);
            previous = height;
        }
    }

    @Test void coastlineSupportsConcavePolygonsWithoutAPolarShortcut() {
        var coast = new Coastline(List.of(new Vec2(-5,-5), new Vec2(5,-5), new Vec2(5,5),
                new Vec2(2,5), new Vec2(2,-1), new Vec2(-2,-1), new Vec2(-2,5), new Vec2(-5,5)));
        assertFalse(coast.contains(0, 0));
        assertTrue(coast.contains(0, -3));
        assertTrue(coast.contains(4, 3));
        assertEquals(-1, coast.signedDistance(0, 0), 1e-9);
    }

    @Test void erosionPreservesShallowOceanAndCannotDrownDryCoast() {
        var delta = new ErosionDeltaField(0, 0, 8, 2, 2, new float[]{-12, -12, -12, -12});
        var ocean = sample(63.9, 64, WaterKind.OCEAN);
        assertEquals(ocean, new ErodedTerrain((x, z) -> ocean, delta, "test").sample(2, 2));
        for (double height = 64; height < 100; height += 0.1) {
            double fixed = height;
            var terrain = new ErodedTerrain((x, z) -> sample(fixed, Double.NaN, WaterKind.NONE), delta, "test");
            assertTrue(terrain.sample(2, 2).groundSurface() >= 64);
        }
    }

    @Test void mountainRidgesHaveReliefWithoutBlockScaleSpikes() {
        var terrain = new MountainTerrain(7331);
        double min = 1000, max = -1000, slope = 0;
        for (int x = -1200; x < 1200; x += 4) for (int z = -1200; z < 1200; z += 4) {
            double h = terrain.sample(x, z);
            min = Math.min(min, h); max = Math.max(max, h);
            slope = Math.max(slope, Math.abs(h - terrain.sample(x + 1, z)));
        }
        assertTrue(max - min > 60);
        assertTrue(slope < 5, "mountain has abrupt spikes: " + slope);
        assertEquals(terrain.sample(53.2, -934.8), new MountainTerrain(7331).sample(53.2, -934.8));
    }
    private static MacroSample sample(double ground, double water, WaterKind kind) {
        return new MacroSample(ground, water, kind, false, "test", "plains", "test");
    }
    private static double radius(Vec2 p) { return Math.hypot(p.x(), p.z()); }
}
