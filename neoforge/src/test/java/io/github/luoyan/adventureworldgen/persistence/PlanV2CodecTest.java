package io.github.luoyan.adventureworldgen.persistence;

import io.github.luoyan.adventureworldgen.config.AdventureWorldConfigParser;
import io.github.luoyan.adventureworldgen.config.ContentId;
import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.StructureAdapter;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.plan.PlanningStage;

class PlanV2CodecTest {
    @Test
    void restoresExactOwnershipAnchorsCapacityAndTemperatureLayout() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":1536},"spawn":{"biome":"minecraft:forest"},
           "biomes":{"filler":["minecraft:desert","minecraft:snowy_plains"],"terrain_rules":{
             "minecraft:desert":{"temperature_level":10,"temperatures":{"very_cold":1,"cold":1,"medium":1,"hot":4}},
             "minecraft:snowy_plains":{"temperature_level":0,"temperatures":{"very_cold":4,"cold":1,"medium":1,"hot":1}}}}}
          """);
        var coast=new Coastline(List.of(new Vec2(-1000,-1000),new Vec2(1000,-1000),new Vec2(1000,1000),new Vec2(-1000,1000)));
        var network=new RiverNetwork(List.of(),List.of(),PlannerProfile.V2.hydrologyVersion());
        var mask=new io.github.luoyan.adventureworldgen.planner.CellMask(new long[]{
                io.github.luoyan.adventureworldgen.planner.CellMask.key(-4,-4),
                io.github.luoyan.adventureworldgen.planner.CellMask.key(4,4),
                io.github.luoyan.adventureworldgen.planner.CellMask.key(200,0)});
        var patch=new PlannedBiomePatch("patch/test",new ContentId("minecraft:forest"),0,-4,-4,204,8,mask,4,4);
        var capacities=new io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan(List.of(
                new io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan.Reservation(0,0,
                        io.github.luoyan.adventureworldgen.terrain.RegionTerrain.Template.MOUNTAINS,null,180.0,48)));
        var original=new GeneratedAdventurePlan(42,config,coast,network,64,128,256,"terrain-v2",null,
                List.of(patch),List.of(),GeneratedAdventurePlan.PlanDiagnostics.basic(coast,network),null,capacities);
        var codec=new PlanV2Codec(); var profile=new ContentId("adventureworldgen:default");
        byte[] encoded=codec.encode(profile,"sparse-input",original);
        var progress=io.github.luoyan.adventureworldgen.runtime.PlanningProgress.begin("reload-test");
        GeneratedAdventurePlan restored;
        try {
            restored=codec.decode(encoded,profile,"sparse-input",config);
            assertEquals(PlanningStage.CACHE,progress.snapshot().stage());
            assertEquals(0,progress.snapshot().percent());
        } finally {io.github.luoyan.adventureworldgen.runtime.PlanningProgress.clear();}
        assertEquals(original.fillerSeedCount(),restored.fillerSeedCount());
        assertArrayEquals(encoded,codec.encode(profile,"sparse-input",restored));
        assertEquals(original.biomePatches(),restored.biomePatches());
        assertEquals(original.capacities().reservations(),restored.capacities().reservations());
        assertArrayEquals(original.climate().actualRatios(),restored.climate().actualRatios());
        assertArrayEquals(original.climate().humidity().actualRatios(),restored.climate().humidity().actualRatios());
        assertEquals(original.recipeRegions(),restored.recipeRegions());
        assertEquals(original.terrainSettings(),restored.terrainSettings());
        assertEquals(original.capacities().ranges(),restored.capacities().ranges());
        var badRecipe=com.google.gson.JsonParser.parseString(new String(encoded,StandardCharsets.UTF_8)).getAsJsonObject();
        badRecipe.getAsJsonObject("terrain").getAsJsonArray("recipe_regions").get(0).getAsJsonObject().addProperty("recipe","VOLCANO");
        assertThrows(RuntimeException.class,()->codec.decode(badRecipe.toString().getBytes(StandardCharsets.UTF_8),profile,"sparse-input",config));
        var badSettings=com.google.gson.JsonParser.parseString(new String(encoded,StandardCharsets.UTF_8)).getAsJsonObject();
        badSettings.getAsJsonObject("terrain").getAsJsonObject("recipe_settings").getAsJsonObject("templates").getAsJsonObject("plains").addProperty("horizontal_scale",2);
        assertThrows(RuntimeException.class,()->codec.decode(badSettings.toString().getBytes(StandardCharsets.UTF_8),profile,"sparse-input",config));
        var malformed=com.google.gson.JsonParser.parseString(new String(encoded,StandardCharsets.UTF_8)).getAsJsonObject();
        malformed.getAsJsonObject("biome_layout").getAsJsonObject("climate").remove("humidity");
        assertThrows(RuntimeException.class,()->codec.decode(malformed.toString().getBytes(StandardCharsets.UTF_8),profile,"sparse-input",config));
        for(int z=-128;z<128;z+=4)for(int x=-128;x<256;x+=4) {
            assertEquals(original.landBiomeAt(x,z),restored.landBiomeAt(x,z));
            assertEquals(original.terrainAt(x,z),restored.terrainAt(x,z));
            assertEquals(original.climate().humidity().valueAt(x,z,original.terrainAt(x,z)),
                    restored.climate().humidity().valueAt(x,z,restored.terrainAt(x,z)));
        }
    }

    @Test
    void canonicalRoundTripRestoresFrozenGeometry() {
        var config = new AdventureWorldConfigParser().parse(new StringReader("""
                {"world":{"radius":6000},"spawn":{"biome":"minecraft:plains"},
                 "biomes":{"required":[],"filler":["minecraft:plains"]},"structures":[]}
                """));
        Coastline coast = new Coastline(List.of(new Vec2(-1000, -1000), new Vec2(1000, -1000),
                new Vec2(1000, 1000), new Vec2(-1000, 1000)));
        var channel = new RiverNetwork.Channel("river/roundtrip", 0, null,
                List.of(new Vec2(-800, 400), new Vec2(0, 300), new Vec2(800, 400)),
                List.of(0.0, Math.hypot(800, 100), 2 * Math.hypot(800, 100)), List.of(70.0, 67.0, 64.0),
                new io.github.luoyan.adventureworldgen.hydrology.HydrologyProfile.RiverShape(8, 2, 6, 20, 30, 0.75), null);
        var network = new RiverNetwork(List.of(channel), List.of(), PlannerProfile.V2.hydrologyVersion());
        var original = new GeneratedAdventurePlan(42, config, coast, network, 64, 128, 256, "terrain-v2", null);
        PlanV2Codec codec = new PlanV2Codec();
        ContentId profile = new ContentId("adventureworldgen:default");
        byte[] encoded = codec.encode(profile, "input", original);
        var restored = codec.decode(encoded, profile, "input", config);

        assertArrayEquals(encoded, codec.encode(profile, "input", restored));
        assertEquals(original.spawnPosition(), restored.spawnPosition());
        assertEquals(network, restored.riverNetwork());
        for (int x = -750; x <= 750; x += 16) for (int z = 250; z <= 450; z += 4)
            assertEquals(original.terrainAt(x, z), restored.terrainAt(x, z), "river shape changed after reload");
        assertEquals(original.terrainAt(37.5, -91.5), restored.terrainAt(37.5, -91.5));
    }

    @Test
    void rejectsUnknownOrMismatchedPayload() {
        var config = new AdventureWorldConfigParser().parse(new StringReader("""
                {"world":{"radius":32},"spawn":{"biome":"minecraft:plains"},
                 "biomes":{"required":[],"filler":["minecraft:plains"]},"structures":[]}
                """));
        byte[] malformed = "{\"format\":\"plan-v1\"}".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> new PlanV2Codec().decode(malformed,
                new ContentId("adventureworldgen:default"), "input", config));
    }

    @Test
    void preservesFrozenEntranceFootprintProtectionAndPieceBytes() {
        var config = new AdventureWorldConfigParser().parse(new StringReader("""
                {"world":{"radius":6000},"spawn":{"biome":"minecraft:plains"},
                 "biomes":{"required":[],"filler":["minecraft:plains"]},
                 "structures":[{"id":"example:waystation","adventure_level":4,
                 "count":{"min":1,"max":1},"allowed_biomes":{"id":["minecraft:plains"]},"entrance":[0,1,-7]}]}
                """));
        Coastline coast = new Coastline(List.of(new Vec2(-1000, -1000), new Vec2(1000, -1000),
                new Vec2(1000, 1000), new Vec2(-1000, 1000)));
        var network = new RiverNetwork(List.of(), List.of(), PlannerProfile.V2.hydrologyVersion());
        var box = new StructureAdapter.HorizontalBox(90, 190, 130, 220);
        var protection = new StructureAdapter.HorizontalBox(82, 182, 138, 228);
        var structure = new AdventurePlanView.PlannedStructure("instance/example:waystation/0",
                new ContentId("example:waystation"), 100, 80, 200, "west", 93, 81, 200,
                List.of(box), List.of(protection), List.of(new AdventurePlanView.PlannedPiece(
                "instance/example:waystation/0/piece/0", 90, 80, 190, 130, 94, 220, new byte[]{3, 1, 4})));
        var original = new GeneratedAdventurePlan(77, config, coast, network, 64, 128, 256, "terrain-v2",
                null, List.of(), List.of(structure), GeneratedAdventurePlan.PlanDiagnostics.basic(coast, network), null);
        var codec = new PlanV2Codec();
        var profile = new ContentId("adventureworldgen:default");
        byte[] encoded = codec.encode(profile, "frozen-input", original);
        var restored = codec.decode(encoded, profile, "frozen-input", config);
        assertArrayEquals(encoded, codec.encode(profile, "frozen-input", restored));
        assertEquals(structure, restored.structures().getFirst());
    }
}
