package io.github.luoyan.adventureworldgen.config;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.AreaRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdventureWorldConfigParserTest {
    private final AdventureWorldConfigParser parser = new AdventureWorldConfigParser();

    @Test
    void parsesDefaultsAndNormalizesSetsWithoutMergingRequiredEntries() {
        AdventureWorldConfig config = parser.parse("""
                {
                  "world": {"radius": 6000},
                  "spawn": {"biome": "example:grassland"},
                  "biomes": {
                    "required": [
                      {"id":"example:grassland", "adventure_level":0},
                      {"id":"example:grassland", "adventure_level":0}
                    ],
                    "filler": ["example:forest", "example:grassland", "example:forest"]
                  }
                }
                """);

        assertEquals(AreaRange.DEFAULT, config.biomes().required().getFirst().area());
        assertEquals("required/0", config.biomes().required().get(0).requestId());
        assertEquals("required/1", config.biomes().required().get(1).requestId());
        assertEquals("patch/required/1", config.biomes().required().get(1).patchId());
        assertEquals("example:forest", config.biomes().filler().get(0).value());
        assertEquals("example:grassland", config.biomes().filler().get(1).value());
        assertTrue(config.structures().isEmpty());
    }

    @Test
    void parsesAndSortsStructureSets() {
        AdventureWorldConfig config = parser.parse(validStructureConfig());

        assertEquals("example:ruins", config.structures().getFirst().id().value());
        assertEquals(1, config.structures().getFirst().effectiveMinimum(true));
        assertEquals(2, config.structures().getFirst().allowedBiomes().ids().size());
        assertEquals("example:forest", config.structures().getFirst().allowedBiomes().ids().getFirst().value());
        assertFalse(config.structures().getFirst().spacing().hasMaximum());
    }

    @Test
    void rejectsUnknownDuplicateAndNullFieldsAtExactPaths() {
        assertPath("""
                {"world":{"radius":1000,"raduis":2},"spawn":{"biome":"example:a"},"biomes":{"filler":["example:a"]}}
                """, "$.world.raduis", ConfigErrorCode.CONFIG_ERROR);
        assertPath("""
                {"world":{"radius":1000,"radius":2000},"spawn":{"biome":"example:a"},"biomes":{"filler":["example:a"]}}
                """, "$.world.radius", ConfigErrorCode.CONFIG_ERROR);
        assertPath("""
                {"world":{"radius":1000},"spawn":{"biome":null},"biomes":{"filler":["example:a"]}}
                """, "$.spawn.biome", ConfigErrorCode.CONFIG_ERROR);
    }

    @Test
    void rejectsInvalidTypesIdentifiersAndNonFiniteNumbers() {
        assertPath("""
                {"world":{"radius":true},"spawn":{"biome":"example:a"},"biomes":{"filler":["example:a"]}}
                """, "$.world.radius", ConfigErrorCode.CONFIG_ERROR);
        assertPath("""
                {"world":{"radius":1e400},"spawn":{"biome":"example:a"},"biomes":{"filler":["example:a"]}}
                """, "$.world.radius", ConfigErrorCode.CONFIG_ERROR);
        assertPath("""
                {"world":{"radius":1000},"spawn":{"biome":"missing_namespace"},"biomes":{"filler":["example:a"]}}
                """, "$.spawn.biome", ConfigErrorCode.CONFIG_ERROR);
    }

    @Test
    void checksAreaQuantityAndSpacingBoundaries() {
        String base = validStructureConfig();
        assertPath(base.replace("\"min\":0,\"max\":1", "\"min\":2,\"max\":1"),
                "$.structures[0].count.max", ConfigErrorCode.CONFIG_CONFLICT);
        assertPath(base.replace("\"entrance\":[0,0,4]", "\"spacing\":{\"min\":3,\"max\":2},\"entrance\":[0,0,4]"),
                "$.structures[0].spacing.max", ConfigErrorCode.CONFIG_CONFLICT);
        assertPath("""
                {"world":{"radius":1000},"spawn":{"biome":"example:a"},"biomes":{"required":[{"id":"example:a","adventure_level":0,"area":{"min":10,"max":265}}],"filler":["example:a"]}}
                """, "$.biomes.required[0].area.max", ConfigErrorCode.CONFIG_CONFLICT);
    }

    @Test
    void validatesSpawnStructureReferenceLevelCountAndBiome() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> parser.parse(
                validStructureConfig().replace("\"adventure_level\":0", "\"adventure_level\":1")));
        assertPath(validStructureConfig().replace("\"min\":0,\"max\":1", "\"min\":0,\"max\":0"),
                "$.spawn.structure.id", ConfigErrorCode.CONFIG_CONFLICT);
        assertPath(validStructureConfig().replace(
                        "\"example:plains\",\"example:forest\",\"example:plains\"", "\"example:desert\""),
                "$.spawn.biome", ConfigErrorCode.CONFIG_CONFLICT);
    }

    @Test
    void acceptsIntegralExponentButRejectsFractionalInteger() {
        AdventureWorldConfig config = parser.parse("""
                {"world":{"radius":1000},"spawn":{"biome":"example:a"},"biomes":{"required":[{"id":"example:a","adventure_level":0e2}],"filler":["example:a"]}}
                """);
        assertEquals(0, config.biomes().required().getFirst().adventureLevel());

        assertPath("""
                {"world":{"radius":1000},"spawn":{"biome":"example:a"},"biomes":{"required":[{"id":"example:a","adventure_level":0.5}],"filler":["example:a"]}}
                """, "$.biomes.required[0].adventure_level", ConfigErrorCode.CONFIG_ERROR);
    }

    @Test
    void canonicalOutputIncludesDefaultsAndIsIdempotent() {
        AdventureWorldConfig parsed = parser.parse(validStructureConfig());
        String first = CanonicalConfigJson.write(parsed);
        String second = CanonicalConfigJson.write(parser.parse(first));

        assertEquals(first, second);
        assertTrue(first.startsWith("{\"biomes\":"));
        assertTrue(first.contains("\"area\":{\"min\":32768,\"target\":393216}"));
        assertTrue(first.contains("\"placement_mode\":\"scattered\""));
        assertTrue(first.contains("\"spacing\":{\"min\":0.0}"));
    }

    @Test
    void terrainRulesRoundTripAndRejectUnknownOrUncoveredTerrain() {
        String json = """
            {"world":{"radius":3000},"spawn":{"biome":"minecraft:plains"},
             "biomes":{"filler":["minecraft:plains","minecraft:desert"],"terrain_rules":{
               "minecraft:desert":{"allowed_terrain":["plateau","plains","plains"],"max_height":112}}}}
            """;
        var config = parser.parse(json);
        assertEquals(CanonicalConfigJson.write(config), CanonicalConfigJson.write(parser.parse(CanonicalConfigJson.write(config))));
        var rule = config.biomes().terrainRules().get(new ContentId("minecraft:desert"));
        assertEquals(2, rule.allowedTerrain().size());
        assertTrue(rule.accepts(new io.github.luoyan.adventureworldgen.api.MacroSample(90, Double.NaN,
                io.github.luoyan.adventureworldgen.api.WaterKind.NONE, false, "r", "plains", "v")));
        assertFalse(rule.accepts(new io.github.luoyan.adventureworldgen.api.MacroSample(160, Double.NaN,
                io.github.luoyan.adventureworldgen.api.WaterKind.NONE, false, "r", "plains", "v")));
        assertThrows(ConfigException.class, () -> parser.parse(json.replace("plateau", "mountain_typo")));
        assertThrows(ConfigException.class, () -> parser.parse(json.replace("112", "null")));
        assertThrows(ConfigException.class, () -> parser.parse(json.replace("112", "100, \"min_height\":120")));
        assertThrows(ConfigException.class, () -> parser.parse(json.replace("[\"minecraft:plains\",\"minecraft:desert\"]", "[\"minecraft:desert\"]")));
    }

    private void assertPath(String json, String path, ConfigErrorCode code) {
        ConfigException error = assertThrows(ConfigException.class, () -> parser.parse(json));
        assertEquals(path, error.path(), error.getMessage());
        assertEquals(code, error.code(), error.getMessage());
    }

    private String validStructureConfig() {
        return """
                {
                  "world":{"radius":6000},
                  "spawn":{"biome":"example:plains","structure":{"id":"example:ruins","spawn_point":[1,2,3]}},
                  "biomes":{"filler":["example:plains"]},
                  "structures":[{
                    "id":"example:ruins",
                    "adventure_level":0,
                    "count":{"min":0,"max":1},
                    "allowed_biomes":{"id":["example:plains","example:forest","example:plains"]},
                    "entrance":[0,0,4]
                  }]
                }
                """;
    }
}
