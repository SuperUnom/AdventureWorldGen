package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.hydrology.*;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;

class BiomeBoundaryTest {
    private GeneratedAdventurePlan plan(String land, boolean water) {
        var config = new AdventureWorldConfigParser().parse(new StringReader("""
                {"world":{"radius":3000},"spawn":{"biome":"minecraft:plains"},
                 "biomes":{"required":[],"filler":[%s]},"structures":[]}
                """.formatted(land)));
        var coast = new Coastline(List.of(new Vec2(-3000,-3000),new Vec2(3000,-3000),new Vec2(3000,3000),new Vec2(-3000,3000)));
        var channel = new RiverNetwork.Channel("test", 0, null, List.of(new Vec2(-1000,0),new Vec2(1000,0)),
                List.of(0.0,2000.0), List.of(64.0,64.0), HydrologyProfile.FINITE_CONTINENT.main(),
                new RiverNetwork.LakeWidening(0.5,60,4));
        var network = new RiverNetwork(water ? List.of(channel) : List.of(), List.of(), "test");
        return new GeneratedAdventurePlan(345705185492107788L,config,coast,network,64,128,256,"test",null);
    }
    @Test void heightLimitFadesIntoPatchesOnItsPermittedSide() {
        var baseline=plan("\"minecraft:forest\",\"minecraft:desert\"",false);
        var config=new AdventureWorldConfigParser().parse("""
            {"world":{"radius":3000},"spawn":{"biome":"minecraft:plains"},
             "biomes":{"filler":["minecraft:forest","minecraft:desert"],"terrain_rules":{
               "minecraft:desert":{"allowed_terrain":["plains","hills","plateau","mountains"],"max_height":92}}}}
            """);
        var capped=new GeneratedAdventurePlan(baseline.seed(),config,baseline.coastline(),
                new RiverNetwork(List.of(),List.of(),"test"),64,128,256,"test",null);
        int retained=0,converted=0;
        for(int z=-2000;z<2000;z+=16)for(int x=-2000;x<2000;x+=16) {
            var land=capped.terrainAt(x+2,z+2);boolean desert=capped.landBiomeAt(x,z).value().equals("minecraft:desert");
            if(desert)assertTrue(land.groundSurface()<=92,"height fade crossed its hard upper bound");
            if(land.groundSurface()>=86&&land.groundSurface()<91&&baseline.landBiomeAt(x,z).value().equals("minecraft:desert")) {
                if(desert)retained++;else converted++;
            }
        }
        assertTrue(retained>10&&converted>10,"height limit still changes the entire biome at one contour");
    }

    @Test void fillersNeverUseForbiddenTerrainEvenWhenNearbySitesAreIneligible() {
        var config = new AdventureWorldConfigParser().parse("""
            {"world":{"radius":3000},"spawn":{"biome":"minecraft:plains"},
             "biomes":{"filler":["minecraft:desert","minecraft:stony_peaks"],"terrain_rules":{
               "minecraft:desert":{"allowed_terrain":["plains","hills","plateau"]},
               "minecraft:stony_peaks":{"allowed_terrain":["mountains"]}}}}
            """);
        var coast = new Coastline(List.of(new Vec2(-3000,-3000),new Vec2(3000,-3000),new Vec2(3000,3000),new Vec2(-3000,3000)));
        var plan = new GeneratedAdventurePlan(345705185492107788L, config, coast,
                new RiverNetwork(List.of(),List.of(),"test"),64,128,256,"test",null);
        int mountains=0, ordinary=0;
        for(int z=-2700;z<2700;z+=64)for(int x=-2700;x<2700;x+=64) {
            var terrain=plan.terrainAt(x+2,z+2);var biome=plan.landBiomeAt(x,z);
            assertTrue(config.biomes().allows(biome,terrain),"forbidden filler at "+x+","+z);
            if(terrain.terrainTemplate().equals("mountains"))mountains++;else ordinary++;
        }
        assertTrue(mountains>100 && ordinary>100);
    }

    @Test void surfaceAndQuartPaletteAlwaysUseTheSameUnwarpedCell() {
        var plan = plan("\"minecraft:plains\",\"minecraft:forest\",\"minecraft:desert\"",false);
        for (int x = -1400; x < 1400; x += 12) for (int z = -1400; z < 1400; z += 12) {
            var expected = plan.biomeAt(x,64,z);
            for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++)
                assertEquals(expected,plan.biomeAt(x+dx,192,z+dz), "uneven biome cell at " + x + "," + z);
        }
    }
    @Test void queryMixingOnlyImportsANearbyRawBiome() {
        var plan = plan("\"minecraft:forest\",\"minecraft:desert\"", false);
        int changed=0;
        for(int z=-1200;z<1200;z+=20)for(int x=-1200;x<1200;x+=4) {
            if(plan.terrainAt(x+2,z+2).waterKind()!=io.github.luoyan.adventureworldgen.api.WaterKind.NONE)continue;
            var raw=plan.landBiomeAt(x,z);var mixed=plan.biomeAt(x,64,z);
            if(raw.equals(mixed))continue;
            changed++;boolean nearby=false;
            for(int dx=-4;dx<=4;dx+=4)for(int dz=-4;dz<=4;dz+=4)
                nearby|=plan.landBiomeAt(x+dx,z+dz).equals(mixed);
            assertTrue(nearby,"mixing imported a distant biome");
            assertEquals(mixed,plan.biomeAt(x,64,z));
        }
        assertTrue(changed>0,"no boundary mixing was exercised");
    }

    @Test void riversAndLakesHaveWaterBiomesWithoutErasingLandOwnership() {
        for (String land : new String[]{"minecraft:plains","minecraft:snowy_plains"}) {
            var plan = plan("\""+land+"\"",true);
            var expected = GeneratedAdventurePlan.inlandWaterBiome(new ContentId(land));
            int rivers = 0, lakes = 0;
            for (int x = -160; x <= 160; x += 4) for (int z = -120; z <= 120; z += 4) {
                var kind = plan.terrainAt(x+2,z+2).waterKind();
                if (kind == io.github.luoyan.adventureworldgen.api.WaterKind.RIVER
                        || kind == io.github.luoyan.adventureworldgen.api.WaterKind.LAKE) {
                    assertEquals(expected,plan.biomeAt(x,64,z));
                    assertEquals(new ContentId(land),plan.landBiomeAt(x,z));
                    if (kind == io.github.luoyan.adventureworldgen.api.WaterKind.RIVER) rivers++; else lakes++;
                }
            }
            assertTrue(rivers > 10 && lakes > 10, "test did not cover both river and lake biome palettes");
        }
    }
}
