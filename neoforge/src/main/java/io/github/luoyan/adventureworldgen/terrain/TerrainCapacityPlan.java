package io.github.luoyan.adventureworldgen.terrain;

import java.util.*;

/**
 * Pre-generation capacity commitments. No generated height is clipped to satisfy a biome rule.
 *
 * <p>Read-only result data: terrain samplers consume this and nothing else. Choosing the
 * commitments is a planning step and lives in {@code planner.TerrainCapacitySolver}; the
 * height-envelope math below is shared with it because it describes terrain, not planning.
 */
public final class TerrainCapacityPlan {
    public record Reservation(long gridX,long gridZ,RegionTerrain.Template template,
                              Double minHeight,Double maxHeight,long reservedArea,
                              TerrainTemplate recipe,TerrainTemplate secondary,Set<String> allowedTemplates,
                              double baseElevation,double amplitude,String strategy) {
        public Reservation {
            allowedTemplates=Set.copyOf(allowedTemplates);
            if(recipe==null||template!=recipe.planningCategory()||!allowedTemplates.contains(recipe.id())
                ||(secondary!=null&&(!allowedTemplates.contains(secondary.id())||recipe.mountain()||secondary.mountain()))
                ||!TerrainTemplate.ids().containsAll(allowedTemplates)
                ||!Double.isFinite(baseElevation)||!Double.isFinite(amplitude)||amplitude<=0||amplitude>220
                ||reservedArea<0||(minHeight!=null&&!Double.isFinite(minHeight))||(maxHeight!=null&&!Double.isFinite(maxHeight))
                ||(minHeight!=null&&maxHeight!=null&&minHeight>maxHeight))throw new IllegalArgumentException("invalid terrain capacity reservation");
        }
        public Reservation(long x,long z,RegionTerrain.Template t,Double min,Double max,long area) {
            this(x,z,t,min,max,area,representative(t),null,Set.of(representative(t).id()),
                legacyFit(t,min,max).base,legacyFit(t,min,max).amplitude,"legacy-fit");
        }
        private static Fit legacyFit(RegionTerrain.Template t,Double min,Double max) {
            var recipe=representative(t);
            var fit=fit(recipe.defaults().verticalAmplitude(),min,max,1.05,recipe.naturalBaseElevation());
            if(fit==null)throw new IllegalArgumentException("height interval cannot hold terrain and erosion");return fit;
        }
    }

    private final List<Reservation> reservations;
    private final Map<RegionTerrain.GridKey,Reservation> lookup;
    private final MountainRangePlan ranges;
    public TerrainCapacityPlan(List<Reservation> reservations) {this(reservations,MountainRangePlan.empty());}
    public TerrainCapacityPlan(List<Reservation> reservations,MountainRangePlan ranges) {
        this.ranges=Objects.requireNonNull(ranges);
        this.reservations=reservations.stream().sorted(Comparator.comparingLong(Reservation::gridX).thenComparingLong(Reservation::gridZ)).toList();
        Map<RegionTerrain.GridKey,Reservation> map=new HashMap<>();
        for(var r:this.reservations)if(map.put(new RegionTerrain.GridKey(r.gridX,r.gridZ),r)!=null)throw new IllegalArgumentException("duplicate reserved region");
        lookup=Map.copyOf(map);
    }
    public static TerrainCapacityPlan empty() { return new TerrainCapacityPlan(List.of()); }
    public List<Reservation> reservations() { return reservations; }
    public MountainRangePlan ranges() { return ranges; }
    public Reservation at(RegionTerrain.GridKey key) { return lookup.get(key); }

    /** Height envelope produced for a template under an allowed height interval. */
    public record Fit(double base,double amplitude,double maximumShape) {}

    /** True when a fitted envelope stays inside the height interval and the world limits. */
    public static boolean acceptsEnvelope(Fit fit,Double min,Double max) {
        double low=64+fit.base-.05*fit.amplitude,high=64+fit.base+fit.maximumShape*fit.amplitude;
        double factor=erosionFactor(high);
        return low>=64&&high+8*factor<=318&&(min==null||low-12*factor>=min)&&(max==null||high+8*factor<=max);
    }
    /** Chooses the largest usable envelope for a template under an allowed height interval. */
    public static Fit fit(double amplitude,Double min,Double max,double maximumShape,double naturalBase) {
        var natural=new Fit(naturalBase,amplitude,maximumShape);if(acceptsEnvelope(natural,min,max))return natural;
        double bottom=min==null?65:Math.max(65,min),top=max==null?310:Math.min(310,max);
        if(top<=bottom)return null;
        // Search lower basins too: their coastal erosion fade gives smaller proven bounds.
        Fit best=null;
        for(double high=top;high>bottom;high-=.125) {
            double factor=erosionFactor(high);
            if(high+8*factor>top)continue;
            double low=bottom+12*factor,available=high-low;
            if(available<.11)continue;
            double fitted=Math.min(amplitude,available/(maximumShape+.05));
            double base=Math.max(low+.05*fitted,Math.min(86,high-maximumShape*fitted))-64;
            var result=new Fit(base,fitted,maximumShape);
            if(acceptsEnvelope(result,min,max)&&(best==null||result.amplitude>best.amplitude))best=result;
            if(fitted==amplitude)break;
        }
        return best;
    }


    private static double erosionFactor(double height) {return TerrainRecipes.smooth(TerrainRecipes.clamp((height-64)/16));}
    private static TerrainTemplate representative(RegionTerrain.Template t) {
        return switch(t){case PLAINS->TerrainTemplate.PLAINS;case HILLS->TerrainTemplate.HILLS_1;case PLATEAU->TerrainTemplate.PLATEAU;case MOUNTAINS->TerrainTemplate.MOUNTAINS_1;};
    }
}
