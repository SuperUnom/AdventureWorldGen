package io.github.luoyan.adventureworldgen.config;

import com.google.gson.JsonParser;
import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.runtime.PlanIdentity;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RoadConfigTest {
    private static final String BASE="{\"world\":{\"radius\":512},\"spawn\":{\"biome\":\"test:plains\"},\"biomes\":{\"filler\":[\"test:plains\"]}";
    @Test void strictTypesAndLimitsRejectSilentCoercions() {
        for(String raw:List.of("{\"width\":4}","{\"width\":3.5}","{\"enabled\":\"true\"}","{\"unknown\":1}","{\"points\":[{\"id\":\"x\",\"x\":1.5,\"z\":0}]}","{\"points\":[{\"id\":\"x\",\"x\":0,\"z\":0,\"required\":\"false\"}]}","{\"maximum_operations\":0}"))
            assertThrows(ConfigException.class,()->RoadConfigJson.read(JsonParser.parseString(raw)),raw);
    }
    @Test void defaultsNormalizeAndRoadInputsChangeIdentity() {
        var parser=new AdventureWorldConfigParser();var config=parser.parse(BASE+"}");
        assertEquals(CanonicalConfigJson.write(config),CanonicalConfigJson.write(parser.parse(BASE+",\"roads\":{}}")));
        var changed=parser.parse(BASE+",\"roads\":{\"enabled\":true,\"bend_amplitude\":18}}");
        assertNotEquals(CanonicalConfigJson.write(config),CanonicalConfigJson.write(changed));
        assertEquals(CanonicalConfigJson.write(changed),CanonicalConfigJson.write(parser.parse(CanonicalConfigJson.write(changed))));
        var id=new ContentId("test:profile");var structure=new ContentId("test:keep");
        var first=new LoadedProfile(id,changed,CanonicalConfigJson.write(changed),"test",StructurePlanningCatalog.of(List.of(new StructurePlanningInfo(structure,new StructurePlanningInfo.RoadAccess(24,48)))));
        var second=new LoadedProfile(id,changed,CanonicalConfigJson.write(changed),"test",StructurePlanningCatalog.of(List.of(new StructurePlanningInfo(structure,new StructurePlanningInfo.RoadAccess(32,48)))));
        var adapters=AdapterRegistry.builder(new BiomeAdapter(){
            public ContentId biomeId(){return new ContentId("test:plains");}
            public String adapterVersion(){return "test";}
            public Compatibility compatibility(MacroSample s){return new Compatibility(true,1,"test");}
        }).build();
        assertNotEquals(PlanIdentity.hash(1,first,adapters,PlannerProfile.V2),PlanIdentity.hash(1,second,adapters,PlannerProfile.V2));
    }

    @Test void removedDestinationListsAreRejectedEvenWhenEmpty() {
        for(var field:List.of("points","biomes","structures"))
            assertThrows(ConfigException.class,()->new AdventureWorldConfigParser().parse(BASE+",\"roads\":{\""+field+"\":[]}}"));
    }
    private String nested(String road) {
        return """
            {"world":{"radius":512},"spawn":{"biome":"test:plains"},
             "biomes":{"filler":["test:plains"],"required":[{"id":"test:plains","adventure_level":1%s}]},
             "structures":[{"id":"test:keep","adventure_level":1,"count":{"min":1,"max":1},"allowed_biomes":{"id":["test:plains"]}%s}],
             "roads":{"enabled":true}}
            """.formatted(road,road);
    }
    @Test void nestedConnectionDefaultsTypesAndCanonicalRoundTrip() {
        var parser=new AdventureWorldConfigParser();var omitted=parser.parse(nested(""));
        assertFalse(omitted.biomes().required().getFirst().road().enabled());assertFalse(omitted.structures().getFirst().road().enabled());
        assertEquals(CanonicalConfigJson.write(omitted),CanonicalConfigJson.write(parser.parse(nested(",\"road\":{}"))));
        for(String value:List.of("null","true","{\"enabled\":\"true\"}","{\"required\":true}","{\"enabled\":true,\"required\":1}","{\"points\":[]}")) {
            var raw=com.google.gson.JsonParser.parseString(nested("")).getAsJsonObject();
            for(var item:List.of(raw.getAsJsonObject("biomes").getAsJsonArray("required").get(0).getAsJsonObject(),raw.getAsJsonArray("structures").get(0).getAsJsonObject())) {
                item.add("road",com.google.gson.JsonParser.parseString(value));
                assertThrows(ConfigException.class,()->parser.parse(raw.toString()),value);item.remove("road");
            }
        }
        var enabled=parser.parse(nested(",\"road\":{\"enabled\":true,\"required\":true}"));
        assertTrue(enabled.biomes().required().getFirst().road().required());assertTrue(enabled.structures().getFirst().road().required());
        assertEquals(enabled,parser.parse(CanonicalConfigJson.write(enabled)));
    }
    @Test void eachNestedConnectionFlagChangesTheInputHash() {
        var parser=new AdventureWorldConfigParser();var baseline=parser.parse(nested(",\"road\":{\"enabled\":true}"));
        var adapters=AdapterRegistry.builder(new BiomeAdapter(){
            public ContentId biomeId(){return new ContentId("test:plains");}
            public String adapterVersion(){return "test";}
            public Compatibility compatibility(MacroSample s){return new Compatibility(true,1,"test");}
        }).build();
        var id=new ContentId("test:profile");
        var before=PlanIdentity.hash(1,new LoadedProfile(id,baseline,CanonicalConfigJson.write(baseline),"test"),adapters,PlannerProfile.V2);
        for(var kind:List.of("biome","structure"))for(var flag:List.of("enabled","required")) {
            var raw=JsonParser.parseString(CanonicalConfigJson.write(baseline)).getAsJsonObject();
            var item=kind.equals("biome")?raw.getAsJsonObject("biomes").getAsJsonArray("required").get(0):raw.getAsJsonArray("structures").get(0);
            item.getAsJsonObject().getAsJsonObject("road").addProperty(flag,flag.equals("required"));
            var changed=parser.parse(raw.toString());
            assertNotEquals(before,PlanIdentity.hash(1,new LoadedProfile(id,changed,CanonicalConfigJson.write(changed),"test"),adapters,PlannerProfile.V2),kind+"."+flag);
        }
    }
    @Test void bundledVillagesAreGeneratedAndRequiredRoadDestinations() throws Exception {
        try(var reader=new java.io.InputStreamReader(getClass().getResourceAsStream("/data/adventureworldgen/adventureworldgen/profiles/default.json"),java.nio.charset.StandardCharsets.UTF_8)) {
            var config=new AdventureWorldConfigParser().parse(reader);assertTrue(config.roads().enabled());assertEquals(3,config.structures().size());
            for(var village:config.structures()) {
                assertEquals(1,village.count().min());assertEquals(1,village.count().max());assertTrue(village.road().enabled());assertTrue(village.road().required());
                try(var access=getClass().getResourceAsStream("/data/minecraft/adventureworldgen/structure_planning/"+village.id().value().split(":")[1]+".json")) {
                    assertNotNull(access);var json=JsonParser.parseString(new String(access.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("road_access");
                    assertDoesNotThrow(()->new StructurePlanningInfo.RoadAccess(json.get("exclusion_radius").getAsInt(),json.get("approach_distance").getAsInt()));
                }
            }
        }
    }
}
