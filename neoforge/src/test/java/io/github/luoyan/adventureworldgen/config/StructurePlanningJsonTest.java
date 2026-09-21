package io.github.luoyan.adventureworldgen.config;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.plan.*;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;

class StructurePlanningJsonTest {
    private StructurePlanningInfo parse(String json) throws Exception {
        var config=new AdventureWorldConfigParser().parse("""
            {"world":{"radius":512},"spawn":{"biome":"test:plains"},"biomes":{"filler":["test:plains"]},
             "structures":[{"id":"test:keep","adventure_level":1,"count":{"min":1,"max":1},"allowed_biomes":{"id":["test:plains"]}}]}
            """);
        return StructurePlanningJson.load(config,id->new StringReader(json)).find(new ContentId("test:keep")).orElseThrow();
    }
    @Test void footprintIsIndependentAndLegacyEnvelopeIsExplicitlyConverted() throws Exception {
        var info=parse("""
            {"footprint":{"min_x":-10,"min_z":-20,"max_x":40,"max_z":50}}
            """);
        assertNull(info.roadAccess());assertEquals(new BoundsXZ(-10,-20,40,50),info.footprint());
        var legacy=parse("""
            {"road_access":{"exclusion_radius":32,"approach_distance":48}}
            """);
        assertEquals(new BoundsXZ(-32,-32,32,32),legacy.footprint());assertEquals(16,legacy.roadAccess().margin());
    }
    @Test void entrancesAreCanonicalAndFieldsRejectCoercion() throws Exception {
        String first="{\"margin\":4,\"entrances\":[{\"x\":40,\"z\":0,\"facing\":\"east\"},{\"x\":0,\"z\":-40,\"facing\":\"north\"}]}";
        String second="{\"entrances\":[{\"facing\":\"north\",\"z\":-40,\"x\":0},{\"z\":0,\"x\":40,\"facing\":\"east\"}],\"margin\":4}";
        assertEquals(parse("{\"road_access\":"+first+"}"),parse("{\"road_access\":"+second+"}"));
        for(String json:java.util.List.of("{\"road_access\":{\"margin\":\"4\"}}","{\"road_access\":{\"margin\":4.5}}",
                "{\"footprint\":{\"min_x\":0,\"max_x\":1,\"min_z\":0}}","{\"road_access\":{\"margin\":4,\"connector_length\":100}}"))
            assertThrows(RuntimeException.class,()->parse(json));
    }
}
