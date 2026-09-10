package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.TemperatureType;

class VanillaAltitudeSnowTest {
    @Test void nativeSnowDoesNotOverrideConfiguredTemperature() {
        var config=new AdventureWorldConfigParser().parse("""
                {"world":{"radius":256},"spawn":{"biome":"minecraft:plains"},
                 "biomes":{"filler":["minecraft:plains","minecraft:windswept_hills"],
                 "terrain_rules":{"minecraft:windswept_hills":{"temperatures":{"cold":1}}}}}
                """);
        var id=new ContentId("minecraft:windswept_hills");
        assertTrue(VanillaAltitudeSnow.snowy(-1686,117,-1910),"reported native snow case regressed");
        for(int height:new int[]{80,180}) {
            MacroTerrain terrain=(x,z)->new MacroSample(height,Double.NaN,WaterKind.NONE,false,"r","hills","test");
            var climate=new ClimatePlan(7331,config,terrain);
            var rules=new BiomeEnvironmentRules(config,climate);
            for(int x=-200;x<=200;x+=32)for(int z=-200;z<=200;z+=32) {
                var sample=terrain.sample(x+2,z+2);
                assertEquals(climate.typeAt(x+2,z+2,sample)==TemperatureType.COLD,
                        rules.prefersType(id,x+2,z+2,sample));
            }
        }
    }
}
