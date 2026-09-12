package io.github.luoyan.adventureworldgen.climate;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.luoyan.adventureworldgen.spatial.CellMask;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.ClimateState;
import io.github.luoyan.adventureworldgen.plan.TemperatureType;
import io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules;
import io.github.luoyan.adventureworldgen.climate.ClimatePlan;
import io.github.luoyan.adventureworldgen.planner.FillerLayout;
import io.github.luoyan.adventureworldgen.planner.JointPlanner;

class HumidityPlanTest {
    private static final ContentId DESERT=new ContentId("minecraft:desert");
    private static final ContentId BEACH=new ContentId("minecraft:beach");
    private static final ContentId SNOW_BEACH=new ContentId("minecraft:snowy_beach");
    private static MacroSample land(double height) {
        return new MacroSample(height,Double.NaN,WaterKind.NONE,false,"r","plains","test");
    }
    private static final MacroTerrain TERRAIN=(x,z)->x>650
            ?new MacroSample(60,64,WaterKind.OCEAN,false,"r","plains","test")
            :x>=260&&x<=284?new MacroSample(61,64,WaterKind.RIVER,false,"r","plains","test"):land(66);
    private static final MacroTerrain NO_RIVER=(x,z)->x>650
            ?new MacroSample(60,64,WaterKind.OCEAN,false,"r","plains","test"):land(66);
    private AdventureWorldConfig config() {
        return new AdventureWorldConfigParser().parse("""
          {"world":{"radius":1024},"spawn":{"biome":"minecraft:plains"},"biomes":{
            "filler":["minecraft:plains","minecraft:desert","minecraft:snowy_plains","minecraft:beach","minecraft:snowy_beach"],
            "terrain_rules":{
              "minecraft:plains":{"humidities":{"dry":1,"medium":3,"wet":2}},
              "minecraft:desert":{"temperatures":{"hot":1},"humidities":{"dry":1}},
              "minecraft:snowy_plains":{"temperatures":{"very_cold":1}},
              "minecraft:beach":{"shore_only":true,"humidities":{"medium":1,"wet":2}},
              "minecraft:snowy_beach":{"shore_only":true,"temperatures":{"very_cold":1},"humidities":{"medium":1,"wet":2}}
          }}}
          """);
    }
    @Test void moistureIsContinuousAndFreshwaterDoesNotRedrawBiomeClimate() {
        var c=config();var temperature=new ClimatePlan(7331,c,TERRAIN,new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));var h=temperature.humidity();
        var noRiver=new ClimatePlan(7331,c,NO_RIVER,new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP)).humidity();
        assertEquals(noRiver.valueAt(246,300,land(66)),h.valueAt(246,300,land(66)),1e-9);
        assertEquals(noRiver.valueAt(302,300,land(66)),h.valueAt(302,300,land(66)),1e-9);
        var dry=new ClimatePlan(7331,c,(x,z)->land(66),new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP)).humidity();
        assertTrue(h.valueAt(630,300,land(66))>dry.valueAt(630,300,land(66))+.1);
        assertTrue(h.valueAt(200,300,land(66))>h.valueAt(200,300,land(300)));
        for(int x=-800;x<640;x++)assertTrue(Math.abs(h.valueAt(x+1,300,land(66))-h.valueAt(x,300,land(66)))<.05);
        assertEquals(1,Arrays.stream(h.actualRatios()).sum(),1e-9);
        assertTrue(h.actualRatios()[0]>0,"field must retain a dry region");
        assertTrue(h.actualRatios()[1]>0);assertTrue(h.actualRatios()[2]>0);
        // A synthetic legacy state forces hot values while retaining the same weather and water fields.
        var state=temperature.snapshot();
        var hotter=new ClimatePlan(7331,c,TERRAIN,ignored->{},new ClimateState(state.extent(),state.slopeHeight(),state.regionalHeight(),
                state.angle(),-101,-97,new double[]{-100,-99,-98},state.snowBoundary(),
                state.spawnType(),state.ratios(),state.actual(),state.corrections(),state.supply(),state.humidity()),new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));
        assertTrue(hotter.humidity().valueAt(-400,300,land(66))<h.valueAt(-400,300,land(66)));
    }
    @Test void beachesOnlyUseOceanShoresAndRemainIntermittent() {
        var c=config();var climate=new ClimatePlan(7331,c,TERRAIN,new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));var h=climate.humidity();
        var rules=new BiomeEnvironmentRules(c,climate);
        var filler=new FillerLayout(PlannerProfile.V2,7331,c,TERRAIN,List.of(),rules);
        int riverBeaches=0,riverOther=0,oceanBeaches=0,oceanOther=0,snowBeaches=0;
        for(int z=-950;z<=950;z+=4) {
            for(int x:new int[]{246,638}) {
                var s=TERRAIN.sample(x,z);var id=filler.biomeAt(x,z,s);
                boolean beach=id.equals(BEACH)||id.equals(SNOW_BEACH);
                if(x==246){if(beach)riverBeaches++;else riverOther++;}
                else {if(beach)oceanBeaches++;else oceanOther++;}
                if(id.equals(SNOW_BEACH))snowBeaches++;
                if(beach)assertEquals(id.equals(SNOW_BEACH),climate.typeAt(x,z,s)==TemperatureType.VERY_COLD);
                assertTrue(rules.allows(id,x,z,s));
            }
        }
        assertEquals(0,riverBeaches,"river banks must not become beach biomes");
        assertTrue(riverOther>0);
        assertTrue(oceanBeaches>0&&oceanOther>0,"coast should contain both beach and other biomes");
        assertEquals(0,snowBeaches,"the accepted field does not create a snow band on this flat warm shore");
        assertFalse(rules.allows(BEACH,0,400,land(66)),"beaches cannot spread inland");
        assertFalse(h.isShore(638,400,land(100)),"cliffs cannot become beaches");
        assertFalse(h.isShore(274,400,TERRAIN.sample(274,400)),"the river bed remains water");
        assertFalse(h.isShore(246,400,TERRAIN.sample(246,400)),"a dry river bank is not an ocean shore");
    }
    @Test void requiredGrowthRespectsHumidityAndReservedShoreCells() {
        var base=config();
        var c=new AdventureWorldConfig(base.world(),base.spawn(),new AdventureWorldConfig.BiomeSettings(
                List.of(new AdventureWorldConfig.RequiredBiome("desert",DESERT,3,new AdventureWorldConfig.AreaRange(4096,8192))),
                base.biomes().filler(),base.biomes().terrainRules()),List.of());
        var climate=new ClimatePlan(7331,c,TERRAIN,new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));
        var rules=new BiomeEnvironmentRules(c,climate);
        var result=new JointPlanner(PlannerProfile.V2).plan(7331,c,TERRAIN,(d,x,y,z,s)->{throw new AssertionError();});
        assertTrue(result.patches().stream().anyMatch(p->p.biomeId().equals(DESERT)&&p.area()>=4096));
        for(var patch:result.patches())for(long cell:patch.mask().cells()) {
            int x=CellMask.x(cell)+2,z=CellMask.z(cell)+2;
            var sample=TERRAIN.sample(x,z);
            assertEquals(WaterKind.NONE,sample.waterKind());
            assertTrue(rules.allows(patch.biomeId(),x,z,sample),patch.patchId());
        }
    }
    @Test void humidityAndFillerReloadWithoutResamplingTerrain() {
        var c=config();var original=new ClimatePlan(7331,c,TERRAIN,new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));
        var frozen=new ClimatePlan(7331,c,(x,z)->{throw new AssertionError("reload sampled terrain");},ignored->{},original.snapshot(),new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));
        var filler=new FillerLayout(PlannerProfile.V2,7331,c,TERRAIN,List.of(),new BiomeEnvironmentRules(c,original));
        var restored=new FillerLayout(PlannerProfile.V2,7331,c,(x,z)->{throw new AssertionError("reload grew filler");},List.of(),new BiomeEnvironmentRules(c,frozen),filler.snapshot());
        assertArrayEquals(original.humidity().actualRatios(),frozen.humidity().actualRatios());
        for(int z=-900;z<900;z+=37)for(int x=-900;x<650;x+=37) {
            var s=TERRAIN.sample(x,z);
            assertEquals(original.humidity().valueAt(x,z,s),frozen.humidity().valueAt(x,z,s));
            assertEquals(filler.biomeAt(x,z,s),restored.biomeAt(x,z,s));
        }
    }
    @Test void moisturePlanningHonorsRestrictedCompositeFillerSupply() {
        var c=new AdventureWorldConfigParser().parse("""
            {"world":{"radius":512},"spawn":{"biome":"example:forest"},"biomes":{"filler":["example:forest"],
             "terrain_rules":{"example:forest":{"humidities":{"medium":2,"wet":3}}}}}
            """);
        MacroTerrain composite=(x,z)->new MacroSample(100,Double.NaN,WaterKind.NONE,false,"r","plains","r21",
                "plains","hills_2",.3,0,0,0,0);
        var climate=new ClimatePlan(9,c,composite,new io.github.luoyan.adventureworldgen.planner.ClimateDiagnostics(c,ClimatePlan.STEP));
        var rules=new BiomeEnvironmentRules(c,climate);
        var filler=new FillerLayout(PlannerProfile.V2,9,c,composite,List.of(),rules);
        for(int x=-450;x<450;x+=19)for(int z=-450;z<450;z+=19) {
            var sample=composite.sample(x,z);var id=filler.biomeAt(x,z,sample);
            assertEquals(new ContentId("example:forest"),id);
            assertTrue(rules.allows(id,x,z,sample));
            assertNotEquals(AdventureWorldConfig.HumidityType.DRY,climate.humidity().typeAt(x,z,sample));
        }
    }

    @Test void configurationRoundTripsAndRejectsInvalidHumidity() {
        var parser=new AdventureWorldConfigParser();String canonical=CanonicalConfigJson.write(config());
        assertEquals(config(),parser.parse(canonical));
        for(String bad:List.of("{}","{\"dry\":0}","{\"wet\":-1}","{\"humid\":1}","null","[]"))
            assertThrows(ConfigException.class,()->parser.parse(canonical.replace("\"humidities\":{\"dry\":1.0}","\"humidities\":"+bad)));
        assertThrows(ConfigException.class,()->parser.parse(canonical.replace("\"shore_only\":true","\"shore_only\":\"true\"")));
        var legacy=parser.parse("""
          {"world":{"radius":64},"spawn":{"biome":"test:a"},"biomes":{"filler":["test:a"],"terrain_rules":{"test:a":{}}}}
          """);
        assertTrue(legacy.biomes().terrainRules().get(new ContentId("test:a")).humidities().isEmpty());
    }
}
