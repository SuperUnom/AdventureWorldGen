package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.*;
import io.github.luoyan.adventureworldgen.terrain.TerrainTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;

class StrictClimateTest {
    @Test void seed7331SnowyFillerCannotCrossTemperatureBoundaryEvenAfterQueryWarp() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":256},"spawn":{"biome":"test:other"},"biomes":{
           "filler":["minecraft:snowy_slopes","test:other"],"terrain_rules":{
            "minecraft:snowy_slopes":{"temperatures":{"very_cold":1}},
            "test:other":{"temperatures":{"cold":1,"medium":1,"hot":1}}}}}
          """);
        MacroTerrain terrain=(x,z)->new MacroSample(80+150*Math.exp(-x*x/(80.0*80)),
                Double.NaN,WaterKind.NONE,false,"r","mountains","test");
        var climate=new ClimatePlan(7331,config,terrain);
        var filler=new FillerLayout(7331,config,terrain,List.of(),climate);
        // Also exercise query fallback when every nearby stored label is snowy.
        var state=filler.snapshot();int[] snowyLabels=state.labels().clone();
        Arrays.fill(snowyLabels,config.biomes().filler().indexOf(new ContentId("minecraft:snowy_slopes")));
        var warped=new FillerLayout(7331,config,terrain,List.of(),climate,
                new FillerLayout.State(state.extent(),snowyLabels,1));
        Set<TemperatureType> seen=EnumSet.noneOf(TemperatureType.class);
        for(int z=-242;z<244;z+=4)for(int x=-242;x<244;x+=4) {
            if(Math.hypot(x,z)>248)continue;
            var sample=terrain.sample(x,z);var type=climate.typeAt(x,z,sample);seen.add(type);
            var expected=new ContentId(type==TemperatureType.VERY_COLD?"minecraft:snowy_slopes":"test:other");
            assertEquals(expected,filler.biomeAt(x,z,sample),"grown filler at "+x+","+z);
            assertEquals(expected,warped.biomeAt(x,z,sample),"query fallback at "+x+","+z);
        }
        assertTrue(seen.containsAll(Set.of(TemperatureType.VERY_COLD,TemperatureType.MEDIUM)));
    }

    @Test void omittedTemperatureIsUnrestrictedAndExplicitLegacyLevelStillConstrains() {
        var parser=new AdventureWorldConfigParser();
        var config=parser.parse("""
          {"world":{"radius":64},"spawn":{"biome":"test:plain"},"biomes":{
           "filler":["test:plain"],"terrain_rules":{
            "test:terrain_only":{"allowed_terrain":["hills"]},
            "test:legacy":{"temperature_level":0}}}}
          """);
        for(var c:List.of(config,parser.parse(CanonicalConfigJson.write(config)))) {
            assertEquals(TemperatureType.unrestricted(),ClimatePlan.preferences(c,new ContentId("test:plain")));
            assertEquals(TemperatureType.unrestricted(),ClimatePlan.preferences(c,new ContentId("test:terrain_only")));
            assertEquals(Map.of(TemperatureType.VERY_COLD,1.0),ClimatePlan.preferences(c,new ContentId("test:legacy")));
        }
    }

    @Test void defaultFillerExplicitlyCoversClimateAndCompositeTerrainCombinations() throws Exception {
        AdventureWorldConfig config;
        try(var in=getClass().getResourceAsStream("/data/adventureworldgen/adventureworldgen/profiles/default.json")) {
            assertNotNull(in);
            config=new AdventureWorldConfigParser().parse(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
        for(var id:config.biomes().filler()) {
            var rule=config.biomes().terrainRules().get(id);assertNotNull(rule,id.value());
            assertFalse(rule.temperatures().isEmpty(),id.value());assertFalse(rule.humidities().isEmpty(),id.value());
        }
        for(var primary:TerrainTemplate.values())for(var secondary:TerrainTemplate.values())
            for(String form:List.of("lowland","foothill","slope","peak")) {
                var sample=new MacroSample(180,Double.NaN,WaterKind.NONE,false,"r",primary.category(),"test",
                        primary.id(),secondary.id(),.25,form.equals("lowland")?0:1,
                        form.equals("slope")||form.equals("peak")?.4:0,form.equals("peak")?30:0,form.equals("peak")?20:0);
                assertEquals(form,sample.landform());
                for(var temperature:TemperatureType.values())for(var humidity:HumidityType.values()) {
                    boolean covered=config.biomes().filler().stream().anyMatch(id->{
                        var rule=config.biomes().terrainRules().get(id);
                        return !rule.shoreOnly()&&rule.accepts(sample)&&rule.temperatures().containsKey(temperature)
                                &&rule.humidities().containsKey(humidity);
                    });
                    assertTrue(covered,primary+"/"+secondary+" "+form+" "+temperature+" "+humidity);
                }
            }
    }
}
