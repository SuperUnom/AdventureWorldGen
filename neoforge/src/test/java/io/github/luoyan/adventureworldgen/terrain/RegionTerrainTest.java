package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionTerrainTest {
    @Test
    void regionSamplingIsStableAcrossNegativeCoordinatesAndUsesPlainCentralRegion() {
        RegionTerrain terrain = new RegionTerrain(1234, PlannerProfile.V2);
        var center = terrain.sample(0, 0);
        assertEquals(RegionTerrain.Template.PLAINS, center.template());
        assertEquals(center, terrain.sample(0, 0));
        assertEquals(terrain.sample(-1500.25, -834.75), terrain.sample(-1500.25, -834.75));
        assertTrue(center.secondDistance() >= center.nearestDistance());
        assertTrue(center.internalWeight() >= 0 && center.internalWeight() <= 1);
    }

    @Test
    void islandCompositionUsesOneContinuousMacroFunctionOnBothSides() {
        var coastResult = new CoastGenerator(PlannerProfile.V2).generate(9, 1000, 100);
        IslandMacroTerrain terrain = new IslandMacroTerrain(coastResult.coastline(),
                new RegionTerrain(9, PlannerProfile.V2), 9, 64,
                coastResult.landBand(), coastResult.seaBand(), "terrain/test");
        var land = terrain.sample(0, 0);
        var sea = terrain.sample(2000, 0);
        assertEquals(WaterKind.NONE, land.waterKind());
        assertEquals(WaterKind.OCEAN, sea.waterKind());
        assertTrue(land.groundSurface() > 64);
        assertTrue(sea.groundSurface() < sea.waterSurface());
        assertEquals(land, terrain.sample(0, 0));
    }

    @Test
    void ordinaryFlatLowlandsReachCloseToSeaLevel() {
        var coast = new Coastline(java.util.List.of(
                new io.github.luoyan.adventureworldgen.spatial.Vec2(-4000,-4000),
                new io.github.luoyan.adventureworldgen.spatial.Vec2(4000,-4000),
                new io.github.luoyan.adventureworldgen.spatial.Vec2(4000,4000),
                new io.github.luoyan.adventureworldgen.spatial.Vec2(-4000,4000)));
        var regions=new RegionTerrain(7331,PlannerProfile.V2);
        var terrain=new IslandMacroTerrain(coast,regions,7331,64,128,256,"test");
        double minimum=Double.POSITIVE_INFINITY;
        for(int z=-300;z<=300;z+=8)for(int x=-300;x<=300;x+=8)
            if(regions.sample(x,z).template()==RegionTerrain.Template.PLAINS)
                minimum=Math.min(minimum,terrain.sample(x,z).groundSurface());
        assertTrue(minimum<74,"flat lowland still starts far above sea level: "+minimum);
    }

    @Test
    void oceanFloorRetainsShallowsNearTheCoast() {
        var coast = new Coastline(java.util.List.of(
                new io.github.luoyan.adventureworldgen.spatial.Vec2(-1000,-1000),
                new io.github.luoyan.adventureworldgen.spatial.Vec2(1000,-1000),
                new io.github.luoyan.adventureworldgen.spatial.Vec2(1000,1000),
                new io.github.luoyan.adventureworldgen.spatial.Vec2(-1000,1000)));
        var terrain=new IslandMacroTerrain(coast,new RegionTerrain(9,PlannerProfile.V2),9,64,128,256,"test");
        assertTrue(terrain.sample(1004,0).waterDepth()<1,"nearshore drop is too steep");
        assertTrue(terrain.sample(1008,0).waterDepth()<1,"shallow coast is missing");
    }
}
