package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.TemperatureType;
import io.github.luoyan.adventureworldgen.plan.ContentId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Demand and supply statistics for the accepted temperature field.
 *
 * <p>Diagnostic only. None of these values feeds back into the temperature field, the humidity
 * field, admission, scoring, or any search decision: the demand ratios and supply accounting exist
 * so the achieved climate mix can be compared with the author's intent, and they are persisted with
 * the layout for that reason. Keeping the computation in one named place makes that independence
 * explicit and lets the statistics be verified on their own.
 *
 * <p>Wire format: {@link Supply} is serialized reflectively as part of the plan-v2 layout state, so
 * its component names and order are part of the persisted format and must not be changed casually.
 * {@code ClimatePlan.State} deliberately keeps flat {@code ratios} / {@code actual} / {@code supply}
 * components rather than nesting this object, because that is the existing wire shape.
 */
public final class ClimateDiagnostics {
    public record Supply(String biome, long target, long legalArea, long climateArea) {}

    /** Read-only temperature observations the statistics need from the field. */
    public interface TemperatureField {
        /** Band ordinal of the accepted field at a coordinate. */
        int band(int x, int z, MacroSample sample);

        /** Unthresholded temperature value used to spread demand across bands. */
        double value(int x, int z, MacroSample sample);
    }

    /** One dry sampled site of the field: the same list the field was built from, same order. */
    public record Site(int x, int z, MacroSample sample) {}

    private final AdventureWorldConfig config;
    private final int step;

    public ClimateDiagnostics(AdventureWorldConfig config, int step) {
        this.config = config;
        this.step = step;
    }

    /** Author demand spread across temperature bands and normalized to sum to one. */
    public double[] targetRatios(List<Site> sites, TemperatureField field) {
        double[] required = new double[4], filler = new double[4];
        for (var d : new RequirementExpander().expandMinimum(config).patches()) {
            double[] weights = d.allowedBiomes().stream().mapToDouble(id -> Math.sqrt(1 + sites.stream()
                    .filter(site -> config.biomes().allows(id, site.sample)).count())).toArray();
            double total = Arrays.stream(weights).sum();
            for (int i = 0; i < weights.length; i++)
                distribute(sites, field, d.allowedBiomes().get(i), d.area().target() * weights[i] / total, required);
        }
        for (var id : config.biomes().filler()) distribute(sites, field, id, BiomeEnvironmentRules.weight(config, id), filler);
        double rt = Arrays.stream(required).sum(), ft = Arrays.stream(filler).sum();
        double[] ratios = new double[4];
        for (int i = 0; i < 4; i++) ratios[i] = .8 * (rt > 0 ? required[i] / rt : 1.0 / 4) + .2 * (ft > 0 ? filler[i] / ft : 1.0 / 4);
        for (int i = 1; i < 4; i++) ratios[i] = Math.max(.04, ratios[i]);
        if (required[0] + filler[0] > 0) ratios[0] = Math.max(.04, ratios[0]);
        double sum = Arrays.stream(ratios).sum();
        for (int i = 0; i < 4; i++) ratios[i] /= sum;
        return ratios;
    }

    /** Share of the dry sampled sites that actually land in each band. */
    public double[] actualRatios(List<Site> sites, TemperatureField field) {
        double[] actual = new double[4];
        for (var s : sites) actual[field.band(s.x, s.z, s.sample)]++;
        for (int i = 0; i < 4; i++) actual[i] /= sites.size();
        return actual;
    }

    /** Per-demand legal and climate area accounting. */
    public List<Supply> supply(List<Site> sites, TemperatureField field) {
        List<Supply> supply = new ArrayList<>();
        for (var d : new RequirementExpander().expandMinimum(config).patches()) {
            ContentId id = preferredBiome(sites, field, d);
            long legal = sites.stream().filter(s -> config.biomes().allows(id, s.sample)).count() * step * step;
            supply.add(new Supply(id.value(), d.area().target(), legal, climateArea(sites, field, id)));
        }
        return List.copyOf(supply);
    }

    private void distribute(List<Site> sites, TemperatureField field, ContentId id, double amount, double[] out) {
        var prefs = BiomeEnvironmentRules.preferences(config, id);
        double[] shares = new double[4];
        for (var e : prefs.entrySet()) {
            double land = 1;
            if (prefs.size() > 1) land += sites.stream().filter(s -> config.biomes().allows(id, s.sample))
                    .filter(s -> {
                        double value = field.value(s.x, s.z, s.sample);
                        return (value < 2.5 ? TemperatureType.VERY_COLD : value < 5 ? TemperatureType.COLD : value < 7.5 ? TemperatureType.MEDIUM : TemperatureType.HOT) == e.getKey();
                    }).count();
            shares[e.getKey().ordinal()] = e.getValue() * Math.sqrt(land);
        }
        double total = Arrays.stream(shares).sum();
        for (int i = 0; i < 4; i++) out[i] += amount * shares[i] / total;
    }

    private ContentId preferredBiome(List<Site> sites, TemperatureField field, RequirementExpander.PatchDemand demand) {
        return demand.allowedBiomes().stream().max(Comparator.comparingLong((ContentId id) -> climateArea(sites, field, id))
                .thenComparing(Comparator.reverseOrder())).orElseThrow();
    }

    private long climateArea(List<Site> sites, TemperatureField field, ContentId id) {
        var prefs = BiomeEnvironmentRules.preferences(config, id);
        return sites.stream().filter(s -> config.biomes().allows(id, s.sample)
                && prefs.containsKey(TemperatureType.values()[field.band(s.x, s.z, s.sample)])).count() * step * step;
    }
}
