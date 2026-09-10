package io.github.luoyan.adventureworldgen.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.AllowedBiomes;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.AreaRange;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.BiomeSettings;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.CountRange;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.PlacementMode;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.RequiredBiome;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.Spacing;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.SpawnSettings;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.SpawnStructure;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.StructureSettings;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.Vec3d;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.WorldSettings;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import io.github.luoyan.adventureworldgen.plan.ContentId;

/** Strict parser for the first author configuration contract. */
public final class AdventureWorldConfigParser {
    private static final Set<String> TOP_FIELDS = Set.of("world", "spawn", "biomes", "structures");
    private static final Set<String> WORLD_FIELDS = Set.of("radius", "terrain");
    private static final Set<String> SPAWN_FIELDS = Set.of("biome", "structure");
    private static final Set<String> SPAWN_STRUCTURE_FIELDS = Set.of("id", "spawn_point");
    private static final Set<String> BIOME_FIELDS = Set.of("required", "filler", "terrain_rules", "blend_radius");
    private static final Set<String> REQUIRED_BIOME_FIELDS = Set.of("id", "adventure_level", "area");
    private static final Set<String> AREA_FIELDS = Set.of("min", "max", "target");
    private static final Set<String> STRUCTURE_FIELDS = Set.of(
            "id", "adventure_level", "count", "allowed_biomes", "placement_mode", "spacing", "entrance");
    private static final Set<String> COUNT_FIELDS = Set.of("min", "max");
    private static final Set<String> ALLOWED_BIOME_FIELDS = Set.of("id", "area");
    private static final Set<String> SPACING_FIELDS = Set.of("min", "max");

    public AdventureWorldConfig parse(Reader source) {
        try {
            StringBuilder json = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                json.append(buffer, 0, count);
            }
            return parse(json.toString());
        } catch (IOException error) {
            throw new ConfigException(ConfigErrorCode.CONFIG_ERROR, "$", "could not read configuration: " + error.getMessage());
        }
    }

    public AdventureWorldConfig parse(String json) {
        rejectDuplicateKeysAndMalformedJson(json);
        final JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(json);
        } catch (JsonParseException error) {
            throw error("$", "malformed JSON: " + error.getMessage());
        }

        JsonObject root = object(rootElement, "$", TOP_FIELDS);
        WorldSettings world = parseWorld(required(root, "world", "$"), "$.world");
        SpawnSettings spawn = parseSpawn(required(root, "spawn", "$"), "$.spawn");
        BiomeSettings biomes = parseBiomes(required(root, "biomes", "$"), "$.biomes");
        List<StructureSettings> structures = root.has("structures")
                ? parseStructures(nonNull(root.get("structures"), "$.structures"), "$.structures")
                : List.of();

        AdventureWorldConfig result = new AdventureWorldConfig(world, spawn, biomes, structures);
        validateCrossFields(result);
        var enabled=result.world().terrain().enabled();
        for(var entry:result.biomes().terrainRules().entrySet()) {
            if(java.util.Collections.disjoint(enabled,entry.getValue().effectiveTemplates()))
                throw conflict("$.biomes.terrain_rules."+entry.getKey(),"no allowed template is enabled");
        }
        for(String template:enabled) {
            boolean covered=result.biomes().filler().stream().anyMatch(id->!result.biomes().terrainRules().containsKey(id)
                || (result.biomes().terrainRules().get(id).effectiveTemplates().contains(template)
                && !result.biomes().terrainRules().get(id).shoreOnly()
                && result.biomes().terrainRules().get(id).landforms().isEmpty()
                && result.biomes().terrainRules().get(id).minHeight()==null && result.biomes().terrainRules().get(id).maxHeight()==null));
            if(!covered)throw conflict("$.biomes.filler","needs a filler without height/landform limits for template "+template);
        }
        return result;
    }

    private WorldSettings parseWorld(JsonElement element, String path) {
        JsonObject object = object(element, path, WORLD_FIELDS);
        double radius = finiteNumber(required(object, "radius", path), path + ".radius");
        if (radius <= 0) {
            throw error(path + ".radius", "must be greater than zero");
        }
        var settings=io.github.luoyan.adventureworldgen.terrain.TerrainSettings.defaults();
        if(object.has("terrain")) {
            String tp=path+".terrain";
            var terrain=object(object.get("terrain"),tp,Set.of("templates","composite","mountain_ranges"));
            var recipes=new java.util.EnumMap<io.github.luoyan.adventureworldgen.terrain.TerrainTemplate,io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.Settings>(io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.class);
            if(terrain.has("templates")) {
                var ts=object(terrain.get("templates"),tp+".templates",io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.ids());
                for(var e:ts.entrySet()) {
                    String rp=tp+".templates."+e.getKey();
                    var r=object(e.getValue(),rp,Set.of("weight","horizontal_scale","vertical_amplitude","detail_strength"));
                    var t=io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.byId(e.getKey());
                    var d=t.defaults();
                    try { recipes.put(t,new io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.Settings(
                        numberOr(r,"weight",rp,d.weight()),numberOr(r,"horizontal_scale",rp,d.horizontalScale()),
                        numberOr(r,"vertical_amplitude",rp,d.verticalAmplitude()),numberOr(r,"detail_strength",rp,d.detailStrength()))); }
                    catch(IllegalArgumentException ex) { throw error(rp,ex.getMessage()); }
                }
            }
            try { settings=new io.github.luoyan.adventureworldgen.terrain.TerrainSettings(recipes,
                booleanOr(terrain,"composite",tp,true),booleanOr(terrain,"mountain_ranges",tp,true)); }
            catch(IllegalArgumentException ex) { throw conflict(tp,ex.getMessage()); }
        }
        return new WorldSettings(radius,settings);
    }

    private double numberOr(JsonObject object,String field,String path,double fallback) {
        return object.has(field)?finiteNumber(object.get(field),path+"."+field):fallback;
    }
    private boolean booleanOr(JsonObject object,String field,String path,boolean fallback) {
        if(!object.has(field))return fallback;
        var value=object.get(field);
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw error(path+"."+field,"must be a boolean");
        return value.getAsBoolean();
    }
    private Set<String> selection(JsonObject object,String field,String path,Set<String> known,Set<String> fallback) {
        if(!object.has(field))return fallback;
        var result=new TreeSet<String>();
        for(var entry:array(object.get(field),path+"."+field)) {
            String value=string(entry,path+"."+field);
            if(!known.contains(value))throw error(path+"."+field,"unknown value: "+value);
            result.add(value);
        }
        if(result.isEmpty())throw conflict(path+"."+field,"must not be empty");
        return Set.copyOf(result);
    }

    private SpawnSettings parseSpawn(JsonElement element, String path) {
        JsonObject object = object(element, path, SPAWN_FIELDS);
        ContentId biome = object.has("biome") ? contentId(nonNull(object.get("biome"), path + ".biome"), path + ".biome") : null;
        SpawnStructure structure = null;
        if (object.has("structure")) {
            String structurePath = path + ".structure";
            JsonObject value = object(nonNull(object.get("structure"), structurePath), structurePath, SPAWN_STRUCTURE_FIELDS);
            structure = new SpawnStructure(
                    contentId(required(value, "id", structurePath), structurePath + ".id"),
                    vector(required(value, "spawn_point", structurePath), structurePath + ".spawn_point"));
        }
        if (biome == null && structure == null) {
            throw conflict(path, "must specify at least one of biome or structure");
        }
        return new SpawnSettings(biome, structure);
    }

    private BiomeSettings parseBiomes(JsonElement element, String path) {
        JsonObject object = object(element, path, BIOME_FIELDS);
        List<RequiredBiome> requiredBiomes = new ArrayList<>();
        if (object.has("required")) {
            JsonArray array = array(nonNull(object.get("required"), path + ".required"), path + ".required");
            for (int index = 0; index < array.size(); index++) {
                String itemPath = path + ".required[" + index + "]";
                JsonObject item = object(nonNull(array.get(index), itemPath), itemPath, REQUIRED_BIOME_FIELDS);
                requiredBiomes.add(new RequiredBiome(
                        "required/" + index,
                        contentId(required(item, "id", itemPath), itemPath + ".id"),
                        adventureLevel(required(item, "adventure_level", itemPath), itemPath + ".adventure_level"),
                        item.has("area") ? area(nonNull(item.get("area"), itemPath + ".area"), itemPath + ".area") : AreaRange.DEFAULT));
            }
        }

        JsonArray fillerArray = array(required(object, "filler", path), path + ".filler");
        if (fillerArray.isEmpty()) {
            throw conflict(path + ".filler", "must contain at least one biome ID");
        }
        TreeSet<ContentId> filler = new TreeSet<>();
        for (int index = 0; index < fillerArray.size(); index++) {
            filler.add(contentId(nonNull(fillerArray.get(index), path + ".filler[" + index + "]"),
                    path + ".filler[" + index + "]"));
        }
        Map<ContentId, AdventureWorldConfig.TerrainRule> rules = new java.util.TreeMap<>();
        if (object.has("terrain_rules")) {
            JsonElement raw = nonNull(object.get("terrain_rules"), path + ".terrain_rules");
            if (!raw.isJsonObject()) throw error(path + ".terrain_rules", "must be an object keyed by biome ID");
            for (var entry : raw.getAsJsonObject().entrySet()) {
                String rulePath = path + ".terrain_rules." + entry.getKey();
                ContentId id = contentId(new JsonPrimitive(entry.getKey()), rulePath);
                JsonObject rule = object(nonNull(entry.getValue(), rulePath), rulePath,
                        Set.of("allowed_terrain", "min_height", "max_height", "temperature_level", "temperatures", "preferred_min_height", "preferred_max_height", "height_penalty", "filler_weight", "adventure_level", "humidities", "shore_only", "allowed_templates", "landforms"));
                JsonArray names = rule.has("allowed_terrain")
                        ? array(nonNull(rule.get("allowed_terrain"), rulePath + ".allowed_terrain"), rulePath + ".allowed_terrain") : null;
                Set<String> allowed = new TreeSet<>();
                if (names == null) allowed.addAll(AdventureWorldConfig.TerrainRule.TEMPLATES);
                else for (JsonElement name : names) {
                    String value = string(name, rulePath + ".allowed_terrain");
                    if (!AdventureWorldConfig.TerrainRule.TEMPLATES.contains(value))
                        throw error(rulePath + ".allowed_terrain", "unknown terrain: " + value);
                    allowed.add(value);
                }
                if (allowed.isEmpty()) throw conflict(rulePath, "allowed_terrain must not be empty");
                Double min = rule.has("min_height") ? finiteNumber(rule.get("min_height"), rulePath + ".min_height") : null;
                Double max = rule.has("max_height") ? finiteNumber(rule.get("max_height"), rulePath + ".max_height") : null;
                if (min != null && max != null && min > max) throw conflict(rulePath, "min_height exceeds max_height");
                long temperature = rule.has("temperature_level") ? integer(rule.get("temperature_level"), rulePath + ".temperature_level") : 5;
                if (temperature < 0 || temperature > 10) throw error(rulePath + ".temperature_level", "must be in [0,10]");
                Map<AdventureWorldConfig.TemperatureType,Double> types = new java.util.EnumMap<>(AdventureWorldConfig.TemperatureType.class);
                if(rule.has("temperatures")) {
                    var ts=object(rule.get("temperatures"),rulePath+".temperatures",Set.of("very_cold","cold","medium","hot"));
                    for(var e:ts.entrySet()) {
                        double weight=finiteNumber(e.getValue(),rulePath+".temperatures."+e.getKey());
                        if(weight<=0)throw error(rulePath+".temperatures", "preferences must be positive");
                        types.put(AdventureWorldConfig.TemperatureType.valueOf(e.getKey().toUpperCase(java.util.Locale.ROOT)),weight);
                    }
                    if(types.isEmpty())throw error(rulePath+".temperatures","must not be empty");
                } else if(rule.has("temperature_level")) {
                    types.put(AdventureWorldConfig.TemperatureType.fromLevel((int)temperature),1.0);
                } else types.putAll(AdventureWorldConfig.TemperatureType.unrestricted());
                Map<AdventureWorldConfig.HumidityType,Double> humidities = new java.util.EnumMap<>(AdventureWorldConfig.HumidityType.class);
                if(rule.has("humidities")) {
                    var hs=object(rule.get("humidities"),rulePath+".humidities",Set.of("dry","medium","wet"));
                    for(var e:hs.entrySet()) {
                        double weight=finiteNumber(e.getValue(),rulePath+".humidities."+e.getKey());
                        if(weight<=0)throw error(rulePath+".humidities", "preferences must be positive");
                        humidities.put(AdventureWorldConfig.HumidityType.valueOf(e.getKey().toUpperCase(java.util.Locale.ROOT)),weight);
                    }
                    if(humidities.isEmpty())throw error(rulePath+".humidities","must not be empty");
                }
                boolean shoreOnly=false;
                if(rule.has("shore_only")) {
                    var value=rule.get("shore_only");
                    if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())
                        throw error(rulePath+".shore_only","must be a boolean");
                    shoreOnly=value.getAsBoolean();
                }
                Double pmin=optionalNumber(rule,"preferred_min_height",rulePath),pmax=optionalNumber(rule,"preferred_max_height",rulePath);
                if(pmin!=null&&pmax!=null&&pmin>pmax)throw conflict(rulePath,"preferred_min_height exceeds preferred_max_height");
                double hp=rule.has("height_penalty")?finiteNumber(rule.get("height_penalty"),rulePath+".height_penalty"):1;
                double fw=rule.has("filler_weight")?finiteNumber(rule.get("filler_weight"),rulePath+".filler_weight"):1;
                if(hp<0||fw<=0)throw error(rulePath,"height_penalty must be nonnegative and filler_weight positive");
                Integer level=rule.has("adventure_level")?adventureLevel(rule.get("adventure_level"),rulePath+".adventure_level"):null;
                var templates=selection(rule,"allowed_templates",rulePath,io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.ids(),io.github.luoyan.adventureworldgen.terrain.TerrainTemplate.ids());
                var landforms=selection(rule,"landforms",rulePath,Set.of("lowland","foothill","slope","peak"),Set.of());
                var parsed=new AdventureWorldConfig.TerrainRule(allowed,min,max,(int)temperature,types,pmin,pmax,hp,fw,level,humidities,shoreOnly,templates,landforms);
                if(parsed.effectiveTemplates().isEmpty())throw conflict(rulePath,"allowed_terrain and allowed_templates have an empty intersection");
                rules.put(id, parsed);
            }
        }
        long blend=object.has("blend_radius")?integer(object.get("blend_radius"),path+".blend_radius"):4;
        if(blend<0||blend>32)throw error(path+".blend_radius","must be in [0,32] blocks");
        return new BiomeSettings(requiredBiomes, List.copyOf(filler), rules,(int)blend);
    }

    private List<StructureSettings> parseStructures(JsonElement element, String path) {
        JsonArray array = array(element, path);
        List<StructureSettings> structures = new ArrayList<>();
        Map<ContentId, String> firstPathById = new HashMap<>();
        for (int index = 0; index < array.size(); index++) {
            String itemPath = path + "[" + index + "]";
            JsonObject item = object(nonNull(array.get(index), itemPath), itemPath, STRUCTURE_FIELDS);
            ContentId id = contentId(required(item, "id", itemPath), itemPath + ".id");
            String previousPath = firstPathById.putIfAbsent(id, itemPath + ".id");
            if (previousPath != null) {
                throw conflict(itemPath + ".id", "duplicates " + previousPath + " (" + id + ")");
            }
            CountRange count = count(required(item, "count", itemPath), itemPath + ".count");
            AllowedBiomes allowedBiomes = allowedBiomes(
                    required(item, "allowed_biomes", itemPath), itemPath + ".allowed_biomes");
            PlacementMode placementMode = PlacementMode.SCATTERED;
            if (item.has("placement_mode")) {
                String value = string(nonNull(item.get("placement_mode"), itemPath + ".placement_mode"),
                        itemPath + ".placement_mode");
                if (!"scattered".equals(value)) {
                    throw error(itemPath + ".placement_mode", "only 'scattered' is supported");
                }
            }
            Spacing spacing = item.has("spacing")
                    ? spacing(nonNull(item.get("spacing"), itemPath + ".spacing"), itemPath + ".spacing")
                    : Spacing.DEFAULT;
            structures.add(new StructureSettings(
                    id,
                    adventureLevel(required(item, "adventure_level", itemPath), itemPath + ".adventure_level"),
                    count,
                    allowedBiomes,
                    placementMode,
                    spacing,
                    vector(required(item, "entrance", itemPath), itemPath + ".entrance")));
        }
        structures.sort(Comparator.comparing(StructureSettings::id));
        return List.copyOf(structures);
    }

    private CountRange count(JsonElement element, String path) {
        JsonObject object = object(element, path, COUNT_FIELDS);
        long min = integer(required(object, "min", path), path + ".min");
        long max = integer(required(object, "max", path), path + ".max");
        if (min < 0) {
            throw error(path + ".min", "must be non-negative");
        }
        if (max < min) {
            throw conflict(path + ".max", "must be greater than or equal to " + path + ".min");
        }
        return new CountRange(min, max);
    }

    private AllowedBiomes allowedBiomes(JsonElement element, String path) {
        JsonObject object = object(element, path, ALLOWED_BIOME_FIELDS);
        JsonArray ids = array(required(object, "id", path), path + ".id");
        TreeSet<ContentId> normalized = new TreeSet<>();
        for (int index = 0; index < ids.size(); index++) {
            normalized.add(contentId(nonNull(ids.get(index), path + ".id[" + index + "]"), path + ".id[" + index + "]"));
        }
        AreaRange area = object.has("area") ? area(nonNull(object.get("area"), path + ".area"), path + ".area") : AreaRange.DEFAULT;
        return new AllowedBiomes(List.copyOf(normalized), area);
    }

    private Double optionalNumber(JsonObject object,String name,String path) {
        return object.has(name)?finiteNumber(object.get(name),path+"."+name):null;
    }

    private AreaRange area(JsonElement element, String path) {
        JsonObject object = object(element, path, AREA_FIELDS);
        long min = integer(required(object, "min", path), path + ".min");
        if(!object.has("max")&&!object.has("target"))throw error(path,"requires target or max");
        long max = object.has("max") ? integer(object.get("max"), path + ".max") : Long.MAX_VALUE;
        long target=object.has("target")?integer(object.get("target"),path+".target"):max;
        if (min <= 0) {
            throw error(path + ".min", "must be greater than zero");
        }
        if (max < min || (!object.has("target") && max - min < 256)) {
            throw conflict(path + ".max", "must be at least 256 greater than " + path + ".min");
        }
        if(target<min||target>max)throw conflict(path+".target","must be between min and max");
        return new AreaRange(min, max, target);
    }

    private Spacing spacing(JsonElement element, String path) {
        JsonObject object = object(element, path, SPACING_FIELDS);
        double min = object.has("min") ? finiteNumber(nonNull(object.get("min"), path + ".min"), path + ".min") : 0.0;
        Double max = object.has("max") ? finiteNumber(nonNull(object.get("max"), path + ".max"), path + ".max") : null;
        if (min < 0) {
            throw error(path + ".min", "must be non-negative");
        }
        if (max != null && max <= 0) {
            throw error(path + ".max", "must be greater than zero");
        }
        if (max != null && max < min) {
            throw conflict(path + ".max", "must be greater than or equal to " + path + ".min");
        }
        return new Spacing(min, max);
    }

    private void validateCrossFields(AdventureWorldConfig config) {
        if (!config.spawn().hasStructure()) {
            return;
        }
        ContentId spawnId = config.spawn().structure().id();
        StructureSettings structure = config.structures().stream()
                .filter(candidate -> candidate.id().equals(spawnId))
                .findFirst()
                .orElseThrow(() -> conflict("$.spawn.structure.id", "does not reference an entry in $.structures"));
        if (structure.count().max() < 1) {
            throw conflict("$.spawn.structure.id", "referenced structure must have count.max >= 1");
        }
        if (config.spawn().hasBiome() && !structure.allowedBiomes().acceptsAnySupportedBiome()
                && !structure.allowedBiomes().ids().contains(config.spawn().biome())) {
            throw conflict("$.spawn.biome", "is not accepted by the spawn structure's $.structures allowed_biomes.id");
        }
    }

    private int adventureLevel(JsonElement element, String path) {
        long value = integer(element, path);
        if (value < 0 || value > 10) {
            throw error(path, "must be an integer from 0 through 10");
        }
        return (int) value;
    }

    private ContentId contentId(JsonElement element, String path) {
        String value = string(element, path);
        try {
            return new ContentId(value);
        } catch (IllegalArgumentException error) {
            throw new ConfigException(ConfigErrorCode.CONFIG_ERROR, path, error.getMessage());
        }
    }

    private Vec3d vector(JsonElement element, String path) {
        JsonArray array = array(element, path);
        if (array.size() != 3) {
            throw error(path, "must contain exactly three numbers");
        }
        return new Vec3d(
                finiteNumber(nonNull(array.get(0), path + "[0]"), path + "[0]"),
                finiteNumber(nonNull(array.get(1), path + "[1]"), path + "[1]"),
                finiteNumber(nonNull(array.get(2), path + "[2]"), path + "[2]"));
    }

    private long integer(JsonElement element, String path) {
        JsonPrimitive primitive = numberPrimitive(element, path, "integer");
        try {
            return new BigDecimal(primitive.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new ConfigException(ConfigErrorCode.CONFIG_ERROR, path, "must be an integer representable as a signed 64-bit value");
        }
    }

    private double finiteNumber(JsonElement element, String path) {
        JsonPrimitive primitive = numberPrimitive(element, path, "number");
        double value;
        try {
            value = Double.parseDouble(primitive.getAsString());
        } catch (NumberFormatException error) {
            throw new ConfigException(ConfigErrorCode.CONFIG_ERROR, path, "must be a finite number");
        }
        if (!Double.isFinite(value)) {
            throw error(path, "must be a finite number");
        }
        return value;
    }

    private JsonPrimitive numberPrimitive(JsonElement element, String path, String expected) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw error(path, "must be a JSON " + expected);
        }
        return element.getAsJsonPrimitive();
    }

    private String string(JsonElement element, String path) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw error(path, "must be a JSON string");
        }
        return element.getAsString();
    }

    private JsonArray array(JsonElement element, String path) {
        if (!element.isJsonArray()) {
            throw error(path, "must be a JSON array");
        }
        return element.getAsJsonArray();
    }

    private JsonObject object(JsonElement element, String path, Set<String> allowedFields) {
        if (!element.isJsonObject()) {
            throw error(path, "must be a JSON object");
        }
        JsonObject object = element.getAsJsonObject();
        for (String name : object.keySet()) {
            if (!allowedFields.contains(name)) {
                throw error(path + "." + name, "unknown field");
            }
        }
        return object;
    }

    private JsonElement required(JsonObject object, String name, String parentPath) {
        if (!object.has(name)) {
            throw error(parentPath + "." + name, "required field is missing");
        }
        return nonNull(object.get(name), parentPath + "." + name);
    }

    private JsonElement nonNull(JsonElement element, String path) {
        if (element == null || element.isJsonNull()) {
            throw error(path, "null is not allowed");
        }
        return element;
    }

    private void rejectDuplicateKeysAndMalformedJson(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            scanValue(reader, "$");
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw error("$", "unexpected content after the root value");
            }
        } catch (ConfigException error) {
            throw error;
        } catch (IOException | IllegalStateException | JsonParseException error) {
            throw new ConfigException(ConfigErrorCode.CONFIG_ERROR, "$", "malformed JSON: " + error.getMessage());
        }
    }

    private void scanValue(JsonReader reader, String path) throws IOException {
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    String childPath = path + "." + name;
                    if (!names.add(name)) {
                        throw error(childPath, "duplicate object key");
                    }
                    scanValue(reader, childPath);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                int index = 0;
                while (reader.hasNext()) {
                    scanValue(reader, path + "[" + index++ + "]");
                }
                reader.endArray();
            }
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw error(path, "expected a JSON value");
        }
    }

    private ConfigException error(String path, String detail) {
        return new ConfigException(ConfigErrorCode.CONFIG_ERROR, path, detail);
    }

    private ConfigException conflict(String path, String detail) {
        return new ConfigException(ConfigErrorCode.CONFIG_CONFLICT, path, detail);
    }
}
