package io.github.luoyan.adventureworldgen.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;

class TerrainTemplateConfigTest {
    private static String profile(String rule,String world) {return "{\"world\":{\"radius\":1000"+world+"},\"spawn\":{\"biome\":\"example:any\"},\"biomes\":{\"filler\":[\"example:any\"],\"terrain_rules\":{\"example:test\":"+rule+"}}}";}
    @Test void rejectsInvalidTemplateIntersectionsAndSettings() {
        var parser=new AdventureWorldConfigParser();
        for(String rule:new String[]{"{\"allowed_templates\":[]}","{\"allowed_templates\":[\"hills\"]}","{\"allowed_templates\":[\"PLAINs\"]}",
            "{\"allowed_terrain\":[\"plains\"],\"allowed_templates\":[\"volcano\"]}","{\"landforms\":[\"ridge\"]}"})
            assertThrows(ConfigException.class,()->parser.parse(profile(rule,"")),rule);
        for(String setting:new String[]{"\"weight\":-1","\"horizontal_scale\":0","\"vertical_amplitude\":0","\"detail_strength\":3","\"weigth\":1"})
            assertThrows(ConfigException.class,()->parser.parse(profile("{}",",\"terrain\":{\"templates\":{\"plains\":{"+setting+"}}}")));
        assertThrows(ConfigException.class,()->parser.parse(profile("{\"allowed_templates\":[\"volcano\"]}",",\"terrain\":{\"templates\":{\"volcano\":{\"weight\":0}}}")));
    }
    @Test void shippedDefaultsKeepRequiredDemandsAndEnableAllRecipes() throws Exception {
        var parser=new AdventureWorldConfigParser();
        var config=parser.parse(Files.readString(Path.of("src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
        assertEquals(12,config.world().terrain().enabled().size());assertEquals(23,config.biomes().required().size());
        assertTrue(config.biomes().filler().contains(new ContentId("minecraft:eroded_badlands")));
        assertTrue(config.world().terrain().get(TerrainTemplate.VOLCANO).weight()<config.world().terrain().get(TerrainTemplate.MOUNTAINS_1).weight());
        assertEquals(CanonicalConfigJson.write(config),CanonicalConfigJson.write(parser.parse(CanonicalConfigJson.write(config))));
    }
}
