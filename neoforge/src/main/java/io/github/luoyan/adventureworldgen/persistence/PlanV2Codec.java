package io.github.luoyan.adventureworldgen.persistence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.erosion.ErosionDeltaField;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyProfile;
import io.github.luoyan.adventureworldgen.hydrology.RiverNetwork;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.terrain.Coastline;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.nio.ByteBuffer;

/** Canonical, explicit plan-v2 payload. No runtime random draw is needed to restore coast or water geometry. */
public final class PlanV2Codec {
    private static final Set<String> ROOT_KEYS = Set.of("algorithm", "format", "hydrology", "input_sha256",
            "operation_counts", "profile", "random_keys", "seed", "spawn", "terrain", "structures", "biome_layout");

    private static final com.google.gson.Gson LAYOUT_JSON=new com.google.gson.Gson();

    public byte[] encode(ContentId profileId, String inputHash, GeneratedAdventurePlan plan) {
        StringWriter output = new StringWriter();
        try (JsonWriter json = new JsonWriter(output)) {
            json.beginObject();
            json.name("algorithm").value(PlannerProfile.V2.algorithmVersion());
            json.name("format").value(PlannerProfile.V2.planFormatVersion());
            json.name("hydrology").value(plan.riverNetwork().version());
            json.name("input_sha256").value(inputHash);
            json.name("operation_counts").beginObject();
            json.name("coast_vertices").value(plan.diagnostics().coastVertices());
            json.name("cost_edges").value(plan.diagnostics().costEdges());
            json.name("cost_nodes").value(plan.diagnostics().costNodes());
            json.name("erosion_operations").value(plan.diagnostics().erosionOperations());
            json.name("erosion_samples").value(plan.diagnostics().erosionSamples());
            json.name("joint_operations").value(plan.diagnostics().jointOperations());
            json.name("river_channels").value(plan.diagnostics().riverChannels());
            json.name("river_points").value(plan.diagnostics().riverPoints());
            json.name("structures").value(plan.structures().size());
            json.name("terrain_version").value(plan.diagnostics().terrainVersion());
            json.endObject();
            json.name("profile").value(profileId.value());
            json.name("random_keys").beginArray();
            json.value("coast-phase"); json.value("erosion"); json.value("filler-biome");
            json.value("hydrology"); json.value("joint-candidate"); json.value("region-center");
            json.value("recipe"); json.value("composite"); json.value("mountain-range"); json.value("structure"); json.value("terrain-noise");
            json.endArray();
            json.name("seed").value(plan.seed());
            json.name("biome_layout");
            LAYOUT_JSON.toJson(plan.biomeLayout(),GeneratedAdventurePlan.BiomeLayout.class,json);
            writeSpawn(json, plan.spawnPosition());
            writeTerrain(json, plan);
            writeStructures(json, plan.structures());
            json.endObject();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    public GeneratedAdventurePlan decode(byte[] bytes, ContentId expectedProfile, String expectedInputHash,
                                         AdventureWorldConfig config) {
        try {
            JsonElement rootElement = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            JsonObject root = object(rootElement, "$", ROOT_KEYS);
            require(root, "format", PlannerProfile.V2.planFormatVersion());
            require(root, "algorithm", PlannerProfile.V2.algorithmVersion());
            require(root, "hydrology", PlannerProfile.V2.hydrologyVersion());
            require(root, "profile", expectedProfile.value());
            require(root, "input_sha256", expectedInputHash);
            long seed = integer(root, "seed");
            JsonObject operations = object(root.get("operation_counts"), "$.operation_counts", Set.of("coast_vertices",
                    "cost_edges", "cost_nodes", "erosion_operations", "erosion_samples", "joint_operations",
                    "river_channels", "river_points", "structures", "terrain_version"));
            var diagnostics = new GeneratedAdventurePlan.PlanDiagnostics(integer(operations, "coast_vertices"),
                    integer(operations, "river_channels"), integer(operations, "river_points"),
                    integer(operations, "erosion_samples"), integer(operations, "erosion_operations"),
                    integer(operations, "cost_nodes"), integer(operations, "cost_edges"),
                    integer(operations, "joint_operations"), string(operations, "terrain_version"));
            AdventurePlanView.SpawnPosition spawn = readSpawn(root.get("spawn"));
            JsonObject terrain = object(root.get("terrain"), "$.terrain",
                    Set.of("biome_patches", "coast", "erosion", "land_band", "river_network", "sea_band", "sea_surface", "terrain_version", "capacity_regions", "mountain_ranges", "recipe_regions", "recipe_settings"));
            double seaSurface = finite(terrain, "sea_surface");
            double landBand = positive(terrain, "land_band");
            double seaBand = positive(terrain, "sea_band");
            String terrainVersion = string(terrain, "terrain_version");
            Coastline coast = new Coastline(readPoints(array(terrain, "coast"), "$.terrain.coast"));
            RiverNetwork network = readNetwork(object(terrain.get("river_network"), "$.terrain.river_network",
                    Set.of("channels", "version", "wetlands")));
            List<PlannedBiomePatch> patches = readPatches(array(terrain, "biome_patches"));
            ErosionDeltaField erosion = terrain.has("erosion") ? readErosion(object(terrain.get("erosion"), "$.terrain.erosion",
                    Set.of("deltas_base64", "height", "operation_count", "origin_x", "origin_z", "spacing", "width"))) : null;
            List<AdventurePlanView.PlannedStructure> structures = readStructures(array(root, "structures"));
            var restored = new GeneratedAdventurePlan(seed, config, coast, network, seaSurface, landBand, seaBand,
                    terrainVersion, spawn, patches, structures, diagnostics, erosion, readCapacities(terrain),
                    java.util.Objects.requireNonNull(LAYOUT_JSON.fromJson(root.get("biome_layout"),GeneratedAdventurePlan.BiomeLayout.class),"missing frozen biome layout"));
            if(!settingsJson(config.world().terrain()).equals(terrain.get("recipe_settings")))
                throw new IllegalArgumentException("frozen recipe settings do not match the active profile");
            // Recipe assignments are explicit plan data. Reject drift rather than silently regenerate them.
            if(!LAYOUT_JSON.toJsonTree(restored.recipeRegions()).equals(terrain.get("recipe_regions")))
                throw new IllegalArgumentException("recipe region manifest does not match frozen terrain inputs");
            return restored;
        } catch (PlanningFailure failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new PlanningFailure(PlanningFailure.Code.EXECUTION_FAILED, "plan-load",
                    "READY plan-v2 payload is invalid", Map.of("reason", String.valueOf(malformed.getMessage())));
        }
    }

    private static void writeSpawn(JsonWriter json, AdventurePlanView.SpawnPosition spawn) throws IOException {
        json.name("spawn").beginObject();
        json.name("x").value(spawn.x()); json.name("y").value(spawn.y());
        json.name("yaw").value(spawn.yaw()); json.name("z").value(spawn.z());
        json.endObject();
    }

    private static void writeTerrain(JsonWriter json, GeneratedAdventurePlan plan) throws IOException {
        json.name("terrain").beginObject();
        json.name("biome_patches").beginArray();
        for (var patch : plan.biomePatches()) {
            json.beginObject();
            json.name("adventure_level").value(patch.adventureLevel());
            json.name("biome").value(patch.biomeId().value());
            json.name("max_x_exclusive").value(patch.maxXExclusive());
            json.name("max_z_exclusive").value(patch.maxZExclusive());
            json.name("min_x").value(patch.minX()); json.name("min_z").value(patch.minZ());
            json.name("patch_id").value(patch.patchId());
            if (patch.mask() != null) {
                json.name("cells_base64").value(patch.mask().encode());
                json.name("anchor_x").value(patch.anchorX()); json.name("anchor_z").value(patch.anchorZ());
            }
            json.endObject();
        }
        json.endArray();
        json.name("coast").beginArray();
        for (Vec2 point : plan.coastline().vertices()) writePoint(json, point);
        json.endArray();
        json.name("recipe_settings"); LAYOUT_JSON.toJson(settingsJson(plan.terrainSettings()),json);
        json.name("recipe_regions"); LAYOUT_JSON.toJson(plan.recipeRegions(),new com.google.gson.reflect.TypeToken<List<io.github.luoyan.adventureworldgen.terrain.RegionTerrain.Region>>(){}.getType(),json);
        json.name("mountain_ranges").beginArray();
        for(var range:plan.capacities().ranges().ranges()) {
            json.beginObject();json.name("id").value(range.id());json.name("width").value(range.width());
            json.name("spine").beginArray();for(var point:range.spine())writePoint(json,point);json.endArray();json.endObject();
        }
        json.endArray();
        json.name("capacity_regions").beginArray();
        for(var r:plan.capacities().reservations()) {
            json.beginObject(); json.name("grid_x").value(r.gridX()); json.name("grid_z").value(r.gridZ());
            json.name("template").value(r.template().name()); json.name("reserved_area").value(r.reservedArea());
            json.name("recipe").value(r.recipe().id());
            if(r.secondary()!=null)json.name("secondary").value(r.secondary().id());
            json.name("allowed_templates").beginArray();for(String t:new java.util.TreeSet<>(r.allowedTemplates()))json.value(t);json.endArray();
            json.name("base_elevation").value(r.baseElevation());json.name("amplitude").value(r.amplitude());json.name("strategy").value(r.strategy());
            if(r.minHeight()!=null)json.name("min_height").value(r.minHeight());
            if(r.maxHeight()!=null)json.name("max_height").value(r.maxHeight());
            json.endObject();
        }
        json.endArray();
        if (plan.erosion() != null) writeErosion(json, plan.erosion());
        json.name("land_band").value(plan.landBand());
        json.name("river_network").beginObject();
        json.name("channels").beginArray();
        for (RiverNetwork.Channel channel : plan.riverNetwork().channels()) {
            json.beginObject();
            json.name("cumulative_lengths"); writeDoubles(json, channel.cumulativeLengths());
            json.name("id").value(channel.id());
            if (channel.lake() != null) {
                json.name("lake").beginObject();
                json.name("along").value(channel.lake().along()); json.name("depth").value(channel.lake().depth());
                json.name("radius").value(channel.lake().radius()); json.endObject();
            }
            json.name("order").value(channel.order());
            if (channel.parentId() != null) json.name("parent_id").value(channel.parentId());
            json.name("points").beginArray(); for (Vec2 point : channel.points()) writePoint(json, point); json.endArray();
            json.name("shape"); writeShape(json, channel.shape());
            json.name("water_surfaces"); writeDoubles(json, channel.waterSurfaces());
            json.endObject();
        }
        json.endArray();
        json.name("version").value(plan.riverNetwork().version());
        json.name("wetlands").beginArray();
        for (RiverNetwork.Wetland wetland : plan.riverNetwork().wetlands()) {
            json.beginObject(); json.name("id").value(wetland.id());
            json.name("radius").value(wetland.radius());
            json.name("upstream"); writePoint(json, wetland.upstream());
            json.name("downstream"); writePoint(json, wetland.downstream());
            json.name("water_surface").value(wetland.waterSurface()); json.endObject();
        }
        json.endArray(); json.endObject();
        json.name("sea_band").value(plan.seaBand());
        json.name("sea_surface").value(plan.seaSurface());
        json.name("terrain_version").value(plan.terrainVersion());
        json.endObject();
    }

    private static io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan readCapacities(JsonObject terrain) {
        var result=new ArrayList<io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan.Reservation>();
        if(terrain.has("capacity_regions"))for(var value:array(terrain,"capacity_regions")) {
            var item=object(value,"capacity_region",Set.of("grid_x","grid_z","template","reserved_area","min_height","max_height","recipe","secondary","allowed_templates","base_elevation","amplitude","strategy"));
            result.add(new io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan.Reservation(integer(item,"grid_x"),integer(item,"grid_z"),
                    io.github.luoyan.adventureworldgen.terrain.RegionTerrain.Template.valueOf(string(item,"template")),
                    item.has("min_height")?finite(item,"min_height"):null,item.has("max_height")?finite(item,"max_height"):null,integer(item,"reserved_area"),
                    io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.byId(string(item,"recipe")),
                    item.has("secondary")?io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.byId(string(item,"secondary")):null,
                    stringSet(array(item,"allowed_templates")),finite(item,"base_elevation"),positive(item,"amplitude"),string(item,"strategy")));
        }
        var ranges=new ArrayList<io.github.luoyan.adventureworldgen.terrain.MountainRangePlan.Range>();
        for(var value:array(terrain,"mountain_ranges")) {
            var item=object(value,"mountain_range",Set.of("id","width","spine"));
            ranges.add(new io.github.luoyan.adventureworldgen.terrain.MountainRangePlan.Range(string(item,"id"),readPoints(array(item,"spine"),"spine"),positive(item,"width")));
        }
        return new io.github.luoyan.adventureworldgen.terrain.TerrainCapacityPlan(result,new io.github.luoyan.adventureworldgen.terrain.MountainRangePlan(ranges));
    }

    private static JsonObject settingsJson(io.github.luoyan.adventureworldgen.terrain.TerrainSettings settings) {
        var result=new JsonObject();result.addProperty("composite",settings.composite());result.addProperty("mountain_ranges",settings.mountainRanges());
        var templates=new JsonObject();
        for(var t:io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.values()) {
            var s=settings.get(t);var value=new JsonObject();value.addProperty("weight",s.weight());
            value.addProperty("horizontal_scale",s.horizontalScale());value.addProperty("vertical_amplitude",s.verticalAmplitude());value.addProperty("detail_strength",s.detailStrength());
            templates.add(t.id(),value);
        }
        result.add("templates",templates);return result;
    }

    private static Set<String> stringSet(JsonArray array) {
        var values=new java.util.TreeSet<String>();
        for(var v:array) {
            if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw new IllegalArgumentException("expected string selection");
            if(!values.add(v.getAsString()))throw new IllegalArgumentException("duplicate selection");
        }
        return Set.copyOf(values);
    }

    private static void writeErosion(JsonWriter json, ErosionDeltaField field) throws IOException {
        float[] deltas = field.copyDeltas();
        ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(deltas.length, Float.BYTES));
        for (float delta : deltas) bytes.putInt(Float.floatToRawIntBits(delta));
        json.name("erosion").beginObject();
        json.name("deltas_base64").value(Base64.getEncoder().encodeToString(bytes.array()));
        json.name("height").value(field.height()); json.name("operation_count").value(field.operationCount());
        json.name("origin_x").value(field.originX()); json.name("origin_z").value(field.originZ());
        json.name("spacing").value(field.spacing()); json.name("width").value(field.width());
        json.endObject();
    }

    private static ErosionDeltaField readErosion(JsonObject root) {
        int width = exactInt(root, "width"), height = exactInt(root, "height");
        byte[] encoded = Base64.getDecoder().decode(string(root, "deltas_base64"));
        int count = Math.multiplyExact(width, height);
        if (encoded.length != Math.multiplyExact(count, Float.BYTES)) throw new IllegalArgumentException("erosion delta byte count mismatch");
        ByteBuffer bytes = ByteBuffer.wrap(encoded); float[] deltas = new float[count];
        for (int i = 0; i < count; i++) deltas[i] = Float.intBitsToFloat(bytes.getInt());
        return new ErosionDeltaField(exactInt(root, "origin_x"), exactInt(root, "origin_z"),
                exactInt(root, "spacing"), width, height, deltas, integer(root, "operation_count"));
    }

    private static void writeStructures(JsonWriter json, List<AdventurePlanView.PlannedStructure> structures) throws IOException {
        json.name("structures").beginArray();
        for (var structure : structures) {
            json.beginObject(); json.name("instance_id").value(structure.instanceId());
            json.name("biome_protection"); writeBoxes(json, structure.biomeProtection());
            json.name("entrance").beginArray().value(structure.entranceX()).value(structure.entranceY())
                    .value(structure.entranceZ()).endArray();
            json.name("footprint"); writeBoxes(json, structure.footprint());
            json.name("origin").beginArray().value(structure.originX()).value(structure.originY()).value(structure.originZ()).endArray();
            json.name("pieces").beginArray();
            for (var piece : structure.pieces()) {
                json.beginObject(); json.name("box").beginArray().value(piece.minX()).value(piece.minY()).value(piece.minZ())
                        .value(piece.maxX()).value(piece.maxY()).value(piece.maxZ()).endArray();
                json.name("nbt_base64").value(Base64.getEncoder().encodeToString(piece.canonicalNbt()));
                json.name("piece_id").value(piece.pieceId()); json.endObject();
            }
            json.endArray(); json.name("rotation").value(structure.rotation());
            json.name("structure_id").value(structure.structureId().value()); json.endObject();
        }
        json.endArray();
    }

    private static List<PlannedBiomePatch> readPatches(JsonArray encoded) {
        List<PlannedBiomePatch> result = new ArrayList<>();
        for (JsonElement element : encoded) {
            JsonObject item = object(element, "biome_patch", Set.of("adventure_level", "biome", "max_x_exclusive",
                    "max_z_exclusive", "min_x", "min_z", "patch_id", "cells_base64", "anchor_x", "anchor_z"));
            result.add(new PlannedBiomePatch(string(item, "patch_id"),
                    new ContentId(string(item, "biome")), exactInt(item, "adventure_level"), exactInt(item, "min_x"),
                    exactInt(item, "min_z"), exactInt(item, "max_x_exclusive"), exactInt(item, "max_z_exclusive"),
                    item.has("cells_base64") ? io.github.luoyan.adventureworldgen.spatial.CellMask.decode(string(item, "cells_base64")) : null,
                    item.has("cells_base64") ? exactInt(item, "anchor_x") : (exactInt(item,"min_x") + exactInt(item,"max_x_exclusive"))/2,
                    item.has("cells_base64") ? exactInt(item, "anchor_z") : (exactInt(item,"min_z") + exactInt(item,"max_z_exclusive"))/2));
        }
        return List.copyOf(result);
    }

    private static List<AdventurePlanView.PlannedStructure> readStructures(JsonArray encoded) {
        List<AdventurePlanView.PlannedStructure> result = new ArrayList<>();
        for (JsonElement element : encoded) {
            JsonObject item = object(element, "structure", Set.of("biome_protection", "entrance", "footprint",
                    "instance_id", "origin", "pieces", "rotation", "structure_id"));
            JsonArray entrance = array(item, "entrance");
            if (entrance.size() != 3) throw new IllegalArgumentException("structure entrance must have three coordinates");
            JsonArray origin = array(item, "origin");
            if (origin.size() != 3) throw new IllegalArgumentException("structure origin must have three coordinates");
            List<AdventurePlanView.PlannedPiece> pieces = new ArrayList<>();
            for (JsonElement pieceElement : array(item, "pieces")) {
                JsonObject piece = object(pieceElement, "piece", Set.of("box", "nbt_base64", "piece_id"));
                JsonArray box = array(piece, "box");
                if (box.size() != 6) throw new IllegalArgumentException("piece box must have six coordinates");
                byte[] nbt = Base64.getDecoder().decode(string(piece, "nbt_base64"));
                pieces.add(new AdventurePlanView.PlannedPiece(string(piece, "piece_id"), box.get(0).getAsInt(),
                        box.get(1).getAsInt(), box.get(2).getAsInt(), box.get(3).getAsInt(), box.get(4).getAsInt(),
                        box.get(5).getAsInt(), nbt));
            }
            result.add(new AdventurePlanView.PlannedStructure(string(item, "instance_id"),
                    new ContentId(string(item, "structure_id")), origin.get(0).getAsInt(), origin.get(1).getAsInt(),
                    origin.get(2).getAsInt(), string(item, "rotation"), entrance.get(0).getAsInt(),
                    entrance.get(1).getAsInt(), entrance.get(2).getAsInt(), readBoxes(array(item, "footprint")),
                    readBoxes(array(item, "biome_protection")), pieces));
        }
        return List.copyOf(result);
    }

    private static void writeBoxes(JsonWriter json, List<io.github.luoyan.adventureworldgen.api.StructureAdapter.HorizontalBox> boxes)
            throws IOException {
        json.beginArray();
        for (var box : boxes) json.beginArray().value(box.minX()).value(box.minZ())
                .value(box.maxX()).value(box.maxZ()).endArray();
        json.endArray();
    }

    private static List<io.github.luoyan.adventureworldgen.api.StructureAdapter.HorizontalBox> readBoxes(JsonArray encoded) {
        List<io.github.luoyan.adventureworldgen.api.StructureAdapter.HorizontalBox> boxes = new ArrayList<>();
        for (JsonElement element : encoded) {
            if (!element.isJsonArray() || element.getAsJsonArray().size() != 4)
                throw new IllegalArgumentException("horizontal box must have four coordinates");
            JsonArray box = element.getAsJsonArray();
            boxes.add(new io.github.luoyan.adventureworldgen.api.StructureAdapter.HorizontalBox(
                    box.get(0).getAsInt(), box.get(1).getAsInt(), box.get(2).getAsInt(), box.get(3).getAsInt()));
        }
        return List.copyOf(boxes);
    }

    private static void writeShape(JsonWriter json, HydrologyProfile.RiverShape shape) throws IOException {
        json.beginObject();
        json.name("bank_width").value(shape.bankWidth()); json.name("bed_depth").value(shape.bedDepth());
        json.name("bed_width").value(shape.bedWidth());
        json.name("maximum_bank_height").value(shape.maximumBankHeight());
        json.name("minimum_bank_height").value(shape.minimumBankHeight());
        json.name("valley_fade").value(shape.fade());
        json.endObject();
    }

    private static RiverNetwork readNetwork(JsonObject root) {
        require(root, "version", PlannerProfile.V2.hydrologyVersion());
        List<RiverNetwork.Channel> channels = new ArrayList<>();
        JsonArray encodedChannels = array(root, "channels");
        for (int i = 0; i < encodedChannels.size(); i++) {
            JsonObject item = object(encodedChannels.get(i), "channel", Set.of("cumulative_lengths", "id", "lake",
                    "order", "parent_id", "points", "shape", "water_surfaces"));
            JsonObject shape = object(item.get("shape"), "shape", Set.of("bank_width", "bed_depth", "bed_width",
                    "maximum_bank_height", "minimum_bank_height", "valley_fade"));
            HydrologyProfile.RiverShape decodedShape = new HydrologyProfile.RiverShape(exactInt(shape, "bed_depth"),
                    exactInt(shape, "minimum_bank_height"), exactInt(shape, "maximum_bank_height"),
                    exactInt(shape, "bank_width"), exactInt(shape, "bed_width"), finite(shape, "valley_fade"));
            RiverNetwork.LakeWidening lake = null;
            if (item.has("lake")) {
                JsonObject encodedLake = object(item.get("lake"), "lake", Set.of("along", "depth", "radius"));
                lake = new RiverNetwork.LakeWidening(finite(encodedLake, "along"), positive(encodedLake, "radius"),
                        positive(encodedLake, "depth"));
            }
            channels.add(new RiverNetwork.Channel(string(item, "id"), Math.toIntExact(integer(item, "order")),
                    item.has("parent_id") ? string(item, "parent_id") : null,
                    readPoints(array(item, "points"), "points"), readDoubles(array(item, "cumulative_lengths")),
                    readDoubles(array(item, "water_surfaces")), decodedShape, lake));
        }
        List<RiverNetwork.Wetland> wetlands = new ArrayList<>();
        JsonArray encodedWetlands = array(root, "wetlands");
        for (int i = 0; i < encodedWetlands.size(); i++) {
            JsonObject item = object(encodedWetlands.get(i), "wetland", Set.of("downstream", "id", "radius", "upstream", "water_surface"));
            wetlands.add(new RiverNetwork.Wetland(string(item, "id"), readPoint(item.get("upstream")),
                    readPoint(item.get("downstream")), positive(item, "radius"), finite(item, "water_surface")));
        }
        return new RiverNetwork(channels, wetlands, string(root, "version"));
    }

    private static AdventurePlanView.SpawnPosition readSpawn(JsonElement element) {
        JsonObject root = object(element, "$.spawn", Set.of("x", "y", "yaw", "z"));
        return new AdventurePlanView.SpawnPosition(finite(root, "x"), finite(root, "y"), finite(root, "z"),
                (float) finite(root, "yaw"));
    }

    private static void writePoint(JsonWriter json, Vec2 point) throws IOException {
        json.beginArray().value(point.x()).value(point.z()).endArray();
    }
    private static void writeDoubles(JsonWriter json, List<Double> values) throws IOException {
        json.beginArray(); for (double value : values) json.value(value); json.endArray();
    }
    private static List<Vec2> readPoints(JsonArray values, String path) {
        List<Vec2> result = new ArrayList<>(values.size());
        for (JsonElement value : values) result.add(readPoint(value));
        if (result.size() < 2) throw new IllegalArgumentException(path + " requires at least two points");
        return List.copyOf(result);
    }
    private static Vec2 readPoint(JsonElement value) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 2)
            throw new IllegalArgumentException("point must have two coordinates");
        double x = value.getAsJsonArray().get(0).getAsDouble(), z = value.getAsJsonArray().get(1).getAsDouble();
        if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("point must be finite");
        return new Vec2(x, z);
    }
    private static List<Double> readDoubles(JsonArray values) {
        List<Double> result = new ArrayList<>(values.size());
        for (JsonElement value : values) {
            double decoded = value.getAsDouble();
            if (!Double.isFinite(decoded)) throw new IllegalArgumentException("array value must be finite");
            result.add(decoded);
        }
        return List.copyOf(result);
    }

    private static JsonObject object(JsonElement value, String path, Set<String> allowed) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(path + " must be an object");
        JsonObject object = value.getAsJsonObject();
        for (String key : object.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException(path + " has unknown key " + key);
        return object;
    }
    private static JsonArray array(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
        return root.getAsJsonArray(key);
    }
    private static String string(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive() || !root.getAsJsonPrimitive(key).isString())
            throw new IllegalArgumentException(key + " must be a string");
        return root.get(key).getAsString();
    }
    private static long integer(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive() || !root.getAsJsonPrimitive(key).isNumber())
            throw new IllegalArgumentException(key + " must be an integer");
        long value = root.get(key).getAsLong();
        if (root.get(key).getAsDouble() != value) throw new IllegalArgumentException(key + " must be an integer");
        return value;
    }
    private static double finite(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive() || !root.getAsJsonPrimitive(key).isNumber())
            throw new IllegalArgumentException(key + " must be a number");
        double value = root.get(key).getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " must be finite");
        return value;
    }
    private static double positive(JsonObject root, String key) {
        double value = finite(root, key); if (!(value > 0)) throw new IllegalArgumentException(key + " must be positive"); return value;
    }
    private static int exactInt(JsonObject root, String key) {
        return Math.toIntExact(integer(root, key));
    }
    private static void require(JsonObject root, String key, String expected) {
        String actual = string(root, key); if (!expected.equals(actual)) throw new IllegalArgumentException(key + " mismatch: " + actual);
    }
}
