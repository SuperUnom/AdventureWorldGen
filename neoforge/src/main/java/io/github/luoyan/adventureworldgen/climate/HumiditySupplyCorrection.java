package io.github.luoyan.adventureworldgen.climate;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Humidity supply correction.
 *
 * <p>The natural moisture field can produce a value that no configured filler biome can legally
 * occupy at a given template and landform. This strategy snaps such a value to the nearest
 * humidity band the filler pool can actually use there.
 *
 * <p>This is a deliberate coupling between the humidity field and the filler pool: changing
 * {@code biomes.filler} can move the environment field. It is a named strategy rather than an
 * inline tail of the field computation so the dependency is visible and replaceable by an explicit
 * decision. It never changes a biome's allowed humidity set, nor the terrain or water geometry.
 *
 * <p><strong>What the cached mask covers.</strong> Supply counts only filler rules that carry no
 * height limit and are not shore-only ({@code minHeight}/{@code maxHeight} and {@code shoreOnly}
 * rules are skipped outright). Everything else the rule asks about - terrain category
 * ({@code allowedTerrain}), primary recipe, whether a secondary recipe actually participates, and
 * landform - is part of the key, so one mask is valid for every position that produces that key.
 * That makes this a <em>template/landform-level</em> supply correction: it says "some filler can
 * use this humidity here", not "this exact position will finally admit a filler". A position can
 * still be rejected later by the full rule set, which is why the correction only snaps humidity
 * into the feasible band and never decides admission. {@code mask == 0} means no filler rule
 * contributed, and the natural value is returned unchanged.
 */
final class HumiditySupplyCorrection {
    private record SupplyKey(String category,String recipe,String secondary,String landform) {}
    private final AdventureWorldConfig config;
    private final Map<SupplyKey,Integer> moistureSupply=new ConcurrentHashMap<>();

    HumiditySupplyCorrection(AdventureWorldConfig config) {
        this.config=config;
    }

    /** Returns {@code value} unchanged when it is already feasible, or when nothing is known. */
    double correct(double value,MacroSample s) {
        // Plan moisture inside the feasible template/landform domain before assigning biomes.
        // This never changes a biome's allowed humidity set, nor the final terrain or water geometry.
        // The key deliberately omits height: height-limited and shore-only rules are excluded from
        // the mask below, so no position that shares this key can reach a different rule set.
        var key=new SupplyKey(s.terrainTemplate(),s.recipe(),s.secondaryWeight()>0?s.secondaryRecipe():"",s.landform());
        int mask=moistureSupply.computeIfAbsent(key,ignored->{
            int result=0;
            for(var id:config.biomes().filler()) {
                var rule=config.biomes().terrainRules().get(id);
                // Height-limited and shore-only fillers are position decisions, not supply: excluding
                // them is what keeps the key honest. Do not drop these conditions without widening
                // the key to the height interval, and treat that as a behaviour change.
                if(rule!=null&&(rule.shoreOnly()||rule.minHeight()!=null||rule.maxHeight()!=null||!rule.accepts(s)))continue;
                if(rule==null||rule.humidities().isEmpty())result=7;
                else for(var type:rule.humidities().keySet())result|=1<<type.ordinal();
            }
            return result;
        });
        int type=value<.38?0:value<.68?1:2;
        if(mask==0||(mask&(1<<type))!=0)return value;
        double best=value,distance=Double.POSITIVE_INFINITY;
        for(int i=0;i<3;i++)if((mask&(1<<i))!=0) {
            double candidate=Math.clamp(value,i==0?0:i==1?.380001:.680001,i==0?.379999:i==1?.679999:1);
            if(Math.abs(candidate-value)<distance){best=candidate;distance=Math.abs(candidate-value);}
        }
        return best;
    }
}
