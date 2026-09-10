package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.TemperatureType;
import io.github.luoyan.adventureworldgen.plan.ContentId;

import java.util.List;
import java.util.Map;

/**
 * The single shared answer to "may this biome be placed here" and "how well does it fit".
 *
 * <p>Seeding, competitive growth, filler selection and boundary mixing all resolve biome ownership
 * through this service, so admission cannot drift between stages, and every stage sees the same
 * temperature admission, humidity admission, hard height cost and coastal-priority rules.
 *
 * <p>It reads the frozen temperature field ({@link ClimatePlan}) and humidity field
 * ({@link HumidityPlan}) plus the author's terrain rules. It never generates either field, and it
 * holds no search state: the methods are pure queries over frozen inputs.
 *
 * <p>Package note: the target layout gives this responsibility to a {@code biome} package. It stays
 * in {@code planner} for now because it reads {@link ClimatePlan} and {@link HumidityPlan}, which
 * still live here; moving it first would create a {@code biome <-> planner} cycle. The
 * responsibility boundary, which is what the plan's exit condition is about, is already in place.
 */
public final class BiomeEnvironmentRules {
    private final AdventureWorldConfig config;
    private final ClimatePlan temperature;
    private final HumidityPlan humidity;
    private final List<ContentId> shores;

    public BiomeEnvironmentRules(AdventureWorldConfig config, ClimatePlan temperature) {
        this.config = config;
        this.temperature = temperature;
        this.humidity = temperature.humidity();
        this.shores = config.biomes().filler().stream().filter(id -> {
            var rule = config.biomes().terrainRules().get(id);
            return rule != null && rule.shoreOnly();
        }).toList();
    }

    /** The frozen temperature field these rules read. */
    public ClimatePlan temperature() { return temperature; }

    /** The frozen humidity field these rules read. */
    public HumidityPlan humidity() { return humidity; }

    /** Configured climate types are admission rules; weights only rank legal candidates. */
    public boolean allows(ContentId id, double x, double z, MacroSample sample) {
        double qx = Math.floor(x / 4) * 4 + 2, qz = Math.floor(z / 4) * 4 + 2;
        if (!prefersType(id, qx, qz, sample) || !humidity.allows(id, qx, qz, sample)) return false;
        var rule = config.biomes().terrainRules().get(id);
        if (rule != null && rule.shoreOnly()) return humidity.isShore(qx, qz, sample);
        if (shores.isEmpty() || !humidity.isShore(qx, qz, sample)) return true;
        for (var shore : shores) if (config.biomes().allows(shore, sample) && prefersType(shore, qx, qz, sample)
                && humidity.allows(shore, qx, qz, sample)) return false;
        return true;
    }

    public boolean prefersType(ContentId id, double x, double z, MacroSample sample) {
        return preferences(config, id).containsKey(temperature.typeAt(x, z, sample));
    }

    /** Number of temperature bands between the biome's allowed set and the field at this point. */
    public int temperatureDistance(ContentId id, double x, double z, MacroSample sample) {
        int band = temperature.typeAt(x, z, sample).ordinal(), best = 3;
        for (var type : preferences(config, id).keySet()) best = Math.min(best, Math.abs(type.ordinal() - band));
        return best;
    }

    /** Preference cost: temperature-band deviation, hard height cost and humidity mismatch. */
    public double cost(ContentId id, double x, double z, MacroSample sample) {
        double value = temperature.valueAt(x, z, sample), best = Double.POSITIVE_INFINITY;
        var prefs = preferences(config, id);
        double max = prefs.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        for (var e : prefs.entrySet()) {
            double center = 1.25 + 2.5 * e.getKey().ordinal();
            double deviation = Math.max(0, Math.abs(center - value) - .8);
            best = Math.min(best, deviation * deviation * .4 - Math.log(e.getValue() / max) * .3);
        }
        var rule = config.biomes().terrainRules().get(id);
        return best + (rule == null ? 0 : rule.heightCost(sample.groundSurface()))
                + (humidity == null ? 0 : humidity.cost(id, x, z, sample));
    }

    /** Allowed temperature bands for a biome: unrestricted unless the author narrowed them. */
    public static Map<TemperatureType, Double> preferences(AdventureWorldConfig config, ContentId id) {
        var rule = config.biomes().terrainRules().get(id);
        return rule == null ? TemperatureType.unrestricted() : rule.temperatures();
    }

    /** Filler weight used to rank and seed filler candidates. */
    public static double weight(AdventureWorldConfig config, ContentId id) {
        var rule = config.biomes().terrainRules().get(id);
        return rule == null ? 1 : rule.fillerWeight();
    }
}
