package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;

class TemperatureLayoutTest {
    @Test void temperatureIsMetadataAndLegacyDefaultIsFive() {
        var parser=new AdventureWorldConfigParser();
        var config=parser.parse("""
          {"world":{"radius":1536},"spawn":{"biome":"minecraft:plains"},"biomes":{"filler":["minecraft:plains"],
            "terrain_rules":{"minecraft:plains":{"temperature_level":0}}}}
          """);
        assertEquals(0,config.biomes().temperature(new ContentId("minecraft:plains")));
        assertEquals(5,config.biomes().temperature(new ContentId("example:unknown")));
        assertTrue(config.biomes().allows(new ContentId("minecraft:plains"),new MacroSample(200,Double.NaN,WaterKind.NONE,false,"r","mountains","v")));
        assertEquals(config,parser.parse(CanonicalConfigJson.write(config)));
        for(String value:List.of("-1","11","2.5","null","\"5\""))
            assertThrows(ConfigException.class,()->parser.parse(CanonicalConfigJson.write(config).replace("\"temperature_level\":0","\"temperature_level\":"+value)));
    }
}
