package io.github.luoyan.adventureworldgen.config;

import com.google.gson.*;
import java.util.Set;

/** Strict optional road object, with one normalized representation used by parsing and identity. */
public final class RoadConfigJson {
    private static final Gson JSON = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
    private RoadConfigJson() {}
    public static JsonElement write(RoadSettings settings) { return JSON.toJsonTree(settings); }
    public static RoadSettings read(JsonElement raw) {
        try {
            JsonObject defaults = write(RoadSettings.disabled()).getAsJsonObject();
            if (!raw.isJsonObject()) throw new IllegalArgumentException("must be an object");
            var input = raw.getAsJsonObject();
            for (var entry : input.entrySet()) {
                String key = entry.getKey(); var value = entry.getValue();
                if (!defaults.has(key)) throw new IllegalArgumentException("unknown road field " + key);
                var shape = defaults.get(key);
                if (!value.isJsonPrimitive()) throw new IllegalArgumentException(key + " has wrong type");
                var p = value.getAsJsonPrimitive(); var d = shape.getAsJsonPrimitive();
                if (d.isBoolean() && !p.isBoolean() || d.isString() && !p.isString() || d.isNumber() && !p.isNumber())
                    throw new IllegalArgumentException(key + " has wrong type");
                if (d.isNumber() && !Set.of("maximum_grade", "bend_spacing", "bend_amplitude", "maximum_bend_detour", "loop_budget_fraction").contains(key))
                    integer(value);
                defaults.add(key, value);
            }
            return JSON.fromJson(defaults, RoadSettings.class);
        } catch (RuntimeException invalid) {
            throw new ConfigException(ConfigErrorCode.CONFIG_ERROR, "$.roads", String.valueOf(invalid.getMessage()));
        }
    }
    private static void integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("expected integer");
        value.getAsBigDecimal().intValueExact();
    }
}
