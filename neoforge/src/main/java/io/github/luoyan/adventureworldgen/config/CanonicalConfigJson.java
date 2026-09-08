package io.github.luoyan.adventureworldgen.config;

import com.google.gson.stream.JsonWriter;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.AllowedBiomes;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.AreaRange;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.Spacing;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.Vec3d;

import java.io.IOException;
import java.io.StringWriter;

/** Writes normalized author configuration with fixed key order and no insignificant whitespace. */
public final class CanonicalConfigJson {
    private CanonicalConfigJson() {}

    public static String write(AdventureWorldConfig config) {
        StringWriter output = new StringWriter();
        try (JsonWriter json = new JsonWriter(output)) {
            json.setSerializeNulls(false);
            json.beginObject();
            json.name("biomes").beginObject();
            json.name("blend_radius").value(config.biomes().blendRadius());
            json.name("filler").beginArray();
            for (ContentId id : config.biomes().filler()) json.value(id.value());
            json.endArray();
            json.name("required").beginArray();
            for (var required : config.biomes().required()) {
                json.beginObject();
                json.name("adventure_level").value(required.adventureLevel());
                writeArea(json, required.area());
                json.name("id").value(required.id().value());
                json.endObject();
            }
            json.endArray();
            if (!config.biomes().terrainRules().isEmpty()) {
                json.name("terrain_rules").beginObject();
                for (var entry : new java.util.TreeMap<>(config.biomes().terrainRules()).entrySet()) {
                    json.name(entry.getKey().value()).beginObject();
                    json.name("allowed_terrain").beginArray();
                    for (String template : new java.util.TreeSet<>(entry.getValue().allowedTerrain())) json.value(template);
                    json.endArray();
                    if (entry.getValue().maxHeight() != null) json.name("max_height").value(entry.getValue().maxHeight());
                    if (entry.getValue().minHeight() != null) json.name("min_height").value(entry.getValue().minHeight());
                    json.name("temperature_level").value(entry.getValue().temperatureLevel());
                    var rule=entry.getValue();
                    json.name("allowed_templates").beginArray();
                    for(String t:new java.util.TreeSet<>(rule.allowedTemplates()))json.value(t);
                    json.endArray();
                    if(!rule.landforms().isEmpty()) {
                        json.name("landforms").beginArray();
                        for(String form:new java.util.TreeSet<>(rule.landforms()))json.value(form);
                        json.endArray();
                    }
                    json.name("temperatures").beginObject();
                    for(var type:AdventureWorldConfig.TemperatureType.values())if(rule.temperatures().containsKey(type))
                        json.name(type.name().toLowerCase(java.util.Locale.ROOT)).value(rule.temperatures().get(type));
                    json.endObject();
                    if(!rule.humidities().isEmpty()) {
                        json.name("humidities").beginObject();
                        for(var type:AdventureWorldConfig.HumidityType.values())if(rule.humidities().containsKey(type))
                            json.name(type.name().toLowerCase(java.util.Locale.ROOT)).value(rule.humidities().get(type));
                        json.endObject();
                    }
                    if(rule.shoreOnly())json.name("shore_only").value(true);
                    if(rule.preferredMinHeight()!=null)json.name("preferred_min_height").value(rule.preferredMinHeight());
                    if(rule.preferredMaxHeight()!=null)json.name("preferred_max_height").value(rule.preferredMaxHeight());
                    json.name("height_penalty").value(rule.heightPenalty());
                    json.name("filler_weight").value(rule.fillerWeight());
                    if(rule.adventureLevel()!=null)json.name("adventure_level").value(rule.adventureLevel());
                    json.endObject();
                }
                json.endObject();
            }
            json.endObject();

            json.name("spawn").beginObject();
            if (config.spawn().biome() != null) json.name("biome").value(config.spawn().biome().value());
            if (config.spawn().structure() != null) {
                json.name("structure").beginObject();
                json.name("id").value(config.spawn().structure().id().value());
                writeVector(json, "spawn_point", config.spawn().structure().spawnPoint());
                json.endObject();
            }
            json.endObject();

            json.name("structures").beginArray();
            for (var structure : config.structures()) {
                json.beginObject();
                json.name("adventure_level").value(structure.adventureLevel());
                writeAllowedBiomes(json, structure.allowedBiomes());
                json.name("count").beginObject();
                json.name("max").value(structure.count().max());
                json.name("min").value(structure.count().min());
                json.endObject();
                writeVector(json, "entrance", structure.entrance());
                json.name("id").value(structure.id().value());
                json.name("placement_mode").value("scattered");
                writeSpacing(json, structure.spacing());
                json.endObject();
            }
            json.endArray();

            json.name("world").beginObject();
            json.name("radius").value(config.world().radius());
            var terrain=config.world().terrain();
            json.name("terrain").beginObject();
            json.name("composite").value(terrain.composite());
            json.name("mountain_ranges").value(terrain.mountainRanges());
            json.name("templates").beginObject();
            for(var t:io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.values()) {
                var s=terrain.get(t); json.name(t.id()).beginObject();
                json.name("weight").value(s.weight()); json.name("horizontal_scale").value(s.horizontalScale());
                json.name("vertical_amplitude").value(s.verticalAmplitude()); json.name("detail_strength").value(s.detailStrength());
                json.endObject();
            }
            json.endObject(); json.endObject();
            json.endObject();
            json.endObject();
        } catch (IOException impossible) {
            throw new IllegalStateException("StringWriter unexpectedly failed", impossible);
        }
        return output.toString();
    }

    private static void writeAllowedBiomes(JsonWriter json, AllowedBiomes allowed) throws IOException {
        json.name("allowed_biomes").beginObject();
        writeArea(json, allowed.area());
        json.name("id").beginArray();
        for (ContentId id : allowed.ids()) json.value(id.value());
        json.endArray();
        json.endObject();
    }

    private static void writeArea(JsonWriter json, AreaRange area) throws IOException {
        json.name("area").beginObject();
        if(area.max()!=Long.MAX_VALUE)json.name("max").value(area.max());
        json.name("min").value(area.min());
        json.name("target").value(area.target());
        json.endObject();
    }

    private static void writeSpacing(JsonWriter json, Spacing spacing) throws IOException {
        json.name("spacing").beginObject();
        if (spacing.max() != null) json.name("max").value(spacing.max());
        json.name("min").value(spacing.min());
        json.endObject();
    }

    private static void writeVector(JsonWriter json, String name, Vec3d vector) throws IOException {
        json.name(name).beginArray();
        json.value(vector.x());
        json.value(vector.y());
        json.value(vector.z());
        json.endArray();
    }
}
