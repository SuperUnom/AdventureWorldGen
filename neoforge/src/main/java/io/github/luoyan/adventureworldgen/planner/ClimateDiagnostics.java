package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.climate.ClimateField;
import io.github.luoyan.adventureworldgen.climate.ClimateStatistics;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.plan.ClimateSupply;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.TemperatureType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules;

/** Converts author demands to pure climate targets and records accepted joint supply. */
public final class ClimateDiagnostics implements ClimateStatistics {
    private final AdventureWorldConfig config;
    private final int step;

    public ClimateDiagnostics(AdventureWorldConfig config, int step) {
        this.config = config;
        this.step = step;
    }

    @Override public List<io.github.luoyan.adventureworldgen.plan.ClimateTarget> targets(List<ClimateField.Site> sites) {
        var result=new ArrayList<io.github.luoyan.adventureworldgen.plan.ClimateTarget>();
        for(var demand:new RequirementExpander().expandMinimum(config).patches()) {
            var options=new ArrayList<io.github.luoyan.adventureworldgen.plan.ClimateTarget.Option>();
            for(var id:demand.allowedBiomes()) {
                var rule=config.biomes().terrainRules().get(id);var eligible=new java.util.BitSet(sites.size());
                for(int i=0;i<sites.size();i++)if(config.biomes().allows(id,sites.get(i).sample()))eligible.set(i);
                int temperatures=0,humidities=0;
                for(var t:config.temperaturePreferences(id).keySet())temperatures|=1<<t.ordinal();
                if(rule==null||rule.humidities().isEmpty())humidities=7;
                else for(var h:rule.humidities().keySet())humidities|=1<<h.ordinal();
                boolean lowlandCold=(temperatures&1)!=0&&(rule==null||rule.minHeight()==null||rule.minHeight()<110)
                        &&(rule==null||!rule.allowedTerrain().equals(java.util.Set.of("mountains")));
                options.add(new io.github.luoyan.adventureworldgen.plan.ClimateTarget.Option(eligible,temperatures,humidities,lowlandCold));
            }
            result.add(new io.github.luoyan.adventureworldgen.plan.ClimateTarget(demand.patchId(),demand.area().target(),demand.requiresSeed(),options));
        }
        return List.copyOf(result);
    }

    /** Author demand spread across temperature bands and normalized to sum to one. */
    @Override
    public double[] targetRatios(ClimateField field, List<ClimateField.Site> sites) {
        double[] required = new double[4], filler = new double[4];
        for (var d : new RequirementExpander().expandMinimum(config).patches()) {
            double[] weights = d.allowedBiomes().stream().mapToDouble(id -> Math.sqrt(1 + sites.stream()
                    .filter(site -> config.biomes().allows(id, site.sample())).count())).toArray();
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
    @Override
    public double[] actualRatios(ClimateField field, List<ClimateField.Site> sites) {
        double[] actual = new double[4];
        for (var s : sites) actual[field.band(s.x(), s.z(), s.sample())]++;
        for (int i = 0; i < 4; i++) actual[i] /= sites.size();
        return actual;
    }

    /** Per-demand legal and climate area accounting. */
    @Override
    public List<ClimateSupply> supply(ClimateField field, List<ClimateField.Site> sites) {
        List<ClimateSupply> supply = new ArrayList<>();
        for (var d : new RequirementExpander().expandMinimum(config).patches()) {
            ContentId id = preferredBiome(sites, field, d);
            long legal = sites.stream().filter(s -> config.biomes().allows(id, s.sample())).count() * step * step;
            long joint=0,lowland=0,cold=0;
            var rule=config.biomes().terrainRules().get(id);
            for(var site:sites) {
                var sample=site.sample();if(!config.biomes().allows(id,sample))continue;
                boolean low=sample.groundSurface()<=110&&!sample.terrainTemplate().equals("mountains");
                if(low)lowland+=step*step;
                int band=field.band(site.x(),site.z(),sample),moisture=field.humidityBand(site.x(),site.z(),sample);
                if(!config.temperaturePreferences(id).containsKey(TemperatureType.values()[band]))continue;
                if(moisture>=0&&rule!=null&&!rule.humidities().isEmpty()
                        &&!rule.humidities().containsKey(AdventureWorldConfig.HumidityType.values()[moisture]))continue;
                joint+=step*step;if(low&&band==0)cold+=step*step;
            }
            String diagnosis=legal==0?"NO_LEGAL_TERRAIN":joint==0?"NO_JOINT_SUPPLY":joint<d.area().min()?"BELOW_REQUESTED_MINIMUM":"AVAILABLE";
            if(config.temperaturePreferences(id).containsKey(TemperatureType.VERY_COLD)&&lowland==0)diagnosis+=";NO_LEGAL_LOWLAND";
            supply.add(new ClimateSupply(id.value(),d.area().target(),legal,climateArea(sites,field,id),joint,lowland,cold,diagnosis));
        }
        return List.copyOf(supply);
    }

    /**
     * Spreads one demand across the four temperature types.
     *
     * <p>The classification comes from {@link ClimateField#band}, exactly as {@link #actualRatios}
     * and {@link #climateArea} read it. It used to rebuild the band from the field's raw value with
     * hardcoded 2.5/5/7.5 thresholds, which silently assumed the field used those thresholds: a
     * frozen field with a different threshold set would have been classified one way here and
     * another way everywhere else. There is deliberately no raw-value accessor to fall back on.
     */
    private void distribute(List<ClimateField.Site> sites, ClimateField field, ContentId id, double amount, double[] out) {
        var prefs = BiomeEnvironmentRules.preferences(config, id);
        double[] shares = new double[4];
        for (var e : prefs.entrySet()) {
            double land = 1;
            if (prefs.size() > 1) {
                int ordinal = e.getKey().ordinal();
                land += sites.stream().filter(s -> config.biomes().allows(id, s.sample()))
                        .filter(s -> field.band(s.x(), s.z(), s.sample()) == ordinal)
                        .count();
            }
            shares[e.getKey().ordinal()] = e.getValue() * Math.sqrt(land);
        }
        double total = Arrays.stream(shares).sum();
        for (int i = 0; i < 4; i++) out[i] += amount * shares[i] / total;
    }

    private ContentId preferredBiome(List<ClimateField.Site> sites, ClimateField field, RequirementExpander.PatchDemand demand) {
        return demand.allowedBiomes().stream().max(Comparator.comparingLong((ContentId id) -> climateArea(sites, field, id))
                .thenComparing(Comparator.reverseOrder())).orElseThrow();
    }

    private long climateArea(List<ClimateField.Site> sites, ClimateField field, ContentId id) {
        var prefs = BiomeEnvironmentRules.preferences(config, id);
        return sites.stream().filter(s -> config.biomes().allows(id, s.sample())
                && prefs.containsKey(TemperatureType.values()[field.band(s.x(), s.z(), s.sample())])).count() * step * step;
    }
}
