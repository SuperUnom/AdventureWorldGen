package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DemandClimateTest {
    private static final MacroTerrain FLAT=(x,z)->new MacroSample(80,Double.NaN,WaterKind.NONE,false,"r","plains","test");
    private AdventureWorldConfig config(int target) {
        return new AdventureWorldConfigParser().parse("""
          {"world":{"radius":768},"spawn":{"biome":"test:temperate"},"biomes":{
           "required":[{"id":"test:hot","adventure_level":5,"area":{"min":4096,"target":%d}}],
           "filler":["test:cold","test:temperate","test:hot"],
           "terrain_rules":{"test:cold":{"temperatures":{"cold":1}},"test:hot":{"temperatures":{"hot":1}}}}}
          """.formatted(target));
    }
    @Test void increasingHotTargetDoesNotRewriteAcceptedTemperatureField() {
        var a=new ClimatePlan(7331,config(8192),FLAT);var b=new ClimatePlan(7331,config(262144),FLAT);
        assertArrayEquals(a.actualRatios(),b.actualRatios());
        for(int z=-700;z<=700;z+=100)for(int x=-700;x<=700;x+=100)
            assertEquals(a.valueAt(x,z,FLAT.sample(x,z)),b.valueAt(x,z,FLAT.sample(x,z)));
        assertArrayEquals(new double[]{2.5,5,7.5},b.snapshot().thresholds());
        assertTrue(b.snapshot().corrections().isEmpty());
        assertEquals(1,java.util.Arrays.stream(b.targetRatios()).sum(),1e-9);
    }
    @Test void targetAndPreferencesRoundTripAndHardHeightRemainsIndependent() {
        var parser=new AdventureWorldConfigParser();
        var config=parser.parse("""
          {"world":{"radius":512},"spawn":{"biome":"test:a"},"biomes":{"blend_radius":4,
           "required":[{"id":"test:a","adventure_level":2,"area":{"min":1024,"target":16384,"max":32768}}],
           "filler":["test:a"],"terrain_rules":{"test:a":{"temperatures":{"cold":1,"medium":3},
           "preferred_min_height":70,"preferred_max_height":100,"height_penalty":2,"filler_weight":4,"adventure_level":3}}}}
          """);
        assertEquals(config,parser.parse(CanonicalConfigJson.write(config)));
        var rule=config.biomes().terrainRules().get(new ContentId("test:a"));
        assertTrue(rule.accepts(new MacroSample(200,Double.NaN,WaterKind.NONE,false,"r","mountains","test")));
        assertTrue(rule.heightCost(200)>rule.heightCost(90));
        assertEquals(16384,config.biomes().required().getFirst().area().target());
        for(String bad:java.util.List.of("\"temperatures\":{}","\"temperatures\":{\"cold\":0}","\"temperatures\":{\"warm\":1}"))
            assertThrows(ConfigException.class,()->parser.parse(CanonicalConfigJson.write(config).replace("\"temperatures\":{\"cold\":1.0,\"medium\":3.0}",bad)));
    }
    @Test void dryGrowthContinuesBeyondTargetWithoutCountingRiverOrCrossingTerrainBarrier() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":256},"spawn":{"biome":"test:a"},"biomes":{
           "required":[{"id":"test:a","adventure_level":0,"area":{"min":1024,"target":16384}}],
           "filler":["test:a"]}}
          """);
        MacroTerrain river=(x,z)->x>60&&x<76?new MacroSample(65,68,WaterKind.RIVER,false,"r","plains","test"):FLAT.sample(x,z);
        var result=new JointPlanner(PlannerProfile.V2).plan(1,config,river,(d,x,y,z,s)->{throw new AssertionError();});
        var patch=result.patches().getFirst();assertTrue(patch.area()>16384);
        for(long cell:patch.mask().cells())assertEquals(WaterKind.NONE,river.sample(CellMask.x(cell)+2,CellMask.z(cell)+2).waterKind());
        assertTrue(patch.maxXExclusive()<=64,"growth crossed an excluded water corridor");
    }
    @Test void targetIsSoftAndOnlyExplicitMaximumCapsArea() {
        assertEquals(Long.MAX_VALUE,config(8192).biomes().required().getFirst().area().max());
        assertEquals(0,BiomeAllocationPlanner.areaPressure(1,false),1e-10);
        assertTrue(BiomeAllocationPlanner.areaPressure(1.001,false)>-.01);
        assertTrue(BiomeAllocationPlanner.areaPressure(2,false)>-2);
    }
    @Test void veryColdIsAConfiguredFourthBandWithSoftFallback() {
        var parser=new AdventureWorldConfigParser();
        var c=parser.parse("""
          {"world":{"radius":512},"spawn":{"biome":"test:temperate"},"biomes":{
           "filler":["test:snow","test:cold","test:temperate","test:hot"],
           "terrain_rules":{"test:snow":{"temperatures":{"very_cold":1}},
            "test:cold":{"temperatures":{"cold":1}},"test:hot":{"temperatures":{"hot":1}}}}}
          """);
        assertEquals(c,parser.parse(CanonicalConfigJson.write(c)));
        var climate=new ClimatePlan(7331,c,FLAT);
        var types=java.util.EnumSet.noneOf(AdventureWorldConfig.TemperatureType.class);
        for(int x=-500;x<500;x+=16)for(int z=-500;z<500;z+=16) {
            var t=climate.typeAt(x+2,z+2,FLAT.sample(x,z));types.add(t);
            assertEquals(t==AdventureWorldConfig.TemperatureType.VERY_COLD,
                climate.prefersType(new ContentId("test:snow"),x+2,z+2,FLAT.sample(x,z)));
            assertTrue(climate.allowsSnowClass(new ContentId("test:cold"),x,z,FLAT.sample(x,z)));
        }
        // Fixed geography does not manufacture all four bands on a small, flat island.
        assertFalse(types.contains(AdventureWorldConfig.TemperatureType.VERY_COLD));
        assertArrayEquals(new double[]{2.5,5,7.5},climate.snapshot().thresholds());
        assertDoesNotThrow(()->parser.parse(CanonicalConfigJson.write(c).replace("\"very_cold\":1.0","\"very_cold\":1.0,\"cold\":1.0")));
    }
    @Test void mountainMassCoolsContinuouslyAndValleysRemainWarmer() {
        var config=config(131072);
        MacroTerrain ridge=(x,z)->new MacroSample(80+160*Math.exp(-x*x/(120.0*120)),Double.NaN,WaterKind.NONE,false,"r","mountains","test");
        var climate=new ClimatePlan(7331,config,ridge);
        assertTrue(climate.elevationCooling(0,400,ridge.sample(0,400))>
                climate.elevationCooling(300,400,ridge.sample(300,400))+.4,"ridge has too little altitude influence");
        for(int x=-400;x<400;x++) {
            double first=climate.valueAt(x,400,ridge.sample(x,400));
            double second=climate.valueAt(x+1,400,ridge.sample(x+1,400));
            assertTrue(Math.abs(first-second)<.15,"temperature contains a hard spatial discontinuity");
        }
    }
}
