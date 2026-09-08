package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.planner.*;
import java.util.*;

/** Pre-generation capacity commitments. No generated height is clipped to satisfy a biome rule. */
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
            var fit=fit(representative(t).defaults().verticalAmplitude(),min,max);
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
    private record Request(String id,ContentId biome,int level,long area) {}
    private static final class Bin {
        final RegionTerrain.GridKey key;
        long area,used; double x,z;
        TerrainTemplate recipe,secondary; Set<String> allowed;
        Double min,max; Fit fit; String strategy;
        Bin(RegionTerrain.GridKey key) { this.key=key; }
    }
    private record Fit(double base,double amplitude,double maximumShape) {}

    public static TerrainCapacityPlan reserve(long seed,AdventureWorldConfig config,Coastline coast,double landBand) {
        var settings=config.world().terrain();
        var ranges=MountainRangePlan.create(seed,config.world().radius(),settings);
        var geometry=new RegionTerrain(seed,PlannerProfile.V2,new TerrainCapacityPlan(List.of(),ranges),settings,config);
        Map<RegionTerrain.GridKey,Bin> bins=new HashMap<>();
        int extent=(int)StrictMath.ceil(config.world().radius()/32)*32;
        for(int z=-extent;z<extent;z+=32)for(int x=-extent;x<extent;x+=32) {
            if(coast.signedDistance(x+16,z+16)<landBand+24)continue;
            // All contributors outside this margin have zero height weight (maximum width 200).
            var key=geometry.interiorRegionAt(x+16,z+16,232);
            if(key==null)continue;
            var bin=bins.computeIfAbsent(key,Bin::new);
            bin.area+=1024;bin.x+=(x+16)*1024.0;bin.z+=(z+16)*1024.0;
        }
        for(var b:bins.values()){b.x/=b.area;b.z/=b.area;}
        var expanded=new RequirementExpander().expandMinimum(config);
        List<Request> requests=new ArrayList<>();
        for(var d:expanded.patches())requests.add(new Request(d.patchId(),d.allowedBiomes().getFirst(),d.adventureLevel(),d.area().inCells(4).min()*16));
        requests.sort(Comparator.comparingInt((Request r)->r.level==0?-1:allowed(config,r.biome).size()).thenComparing(Request::id));
        var central=geometry.regionKeyAt(0,0);
        for(var request:requests) {
            var rule=config.biomes().terrainRules().get(request.biome);
            Double min=rule==null?null:rule.minHeight(),max=rule==null?null:rule.maxHeight();
            long remaining=request.area;
            var choices=new ArrayList<>(bins.values());
            choices.sort(Comparator.comparingDouble((Bin b)-> {
                double target=config.world().radius()*request.level/10.0;
                double score=Math.abs(StrictMath.hypot(b.x,b.z)-target);
                if(request.level==0&&b.key.equals(central))score-=10000;
                return score;
            }).thenComparingLong(b->b.key.x()).thenComparingLong(b->b.key.z()));
            int incompatible=0,heightFailures=0;
            // Prefer naturally legal envelopes anywhere, then fit allowed recipes, then isolated lowland.
            for(int pass=0;pass<3&&remaining>0;pass++)for(var bin:choices) {
                if(bin.used>=bin.area)continue;
                var possible=new TreeSet<>(allowed(config,request.biome));
                if(bin.allowed!=null)possible.retainAll(bin.allowed);
                if(possible.isEmpty()){incompatible++;continue;}
                Double lo=maxNullable(bin.min,min),hi=minNullable(bin.max,max);
                var natural=geometry.region(bin.key.x(),bin.key.z());
                var recipe=bin.recipe!=null?bin.recipe:natural.recipe();
                var secondary=bin.recipe!=null?bin.secondary:natural.secondary();
                boolean naturalAllowed=possible.contains(recipe.id())&&(secondary==null||possible.contains(secondary.id()));
                if(pass==0&&!naturalAllowed)continue;
                if(pass>0&&!naturalAllowed) {
                    // The bin can be reassigned only before anyone has reserved it.
                    if(bin.used>0)continue;
                    recipe=possible.stream().map(TerrainTemplate::byId)
                        .min(Comparator.comparingDouble(t->settings.get(t).verticalAmplitude())).orElseThrow();
                    secondary=null;
                }
                double amplitude=settings.get(recipe).verticalAmplitude();
                if(secondary!=null)amplitude=Math.max(amplitude,settings.get(secondary).verticalAmplitude());
                double maximumShape=settings.maximumShape(recipe,secondary);
                var fitted=bin.fit!=null?bin.fit:new Fit(natural.baseElevation(),amplitude,maximumShape);
                if(pass==0&&!acceptsEnvelope(fitted,lo,hi))continue;
                if(pass>0) {
                    if(pass==1&&lo!=null&&hi!=null&&hi-lo<20)continue;
                    if(pass==2&&(hi==null||hi>90||bin.used>0||recipe.mountain()))continue;
                    fitted=fit(amplitude,lo,hi,maximumShape);
                    if(fitted==null){heightFailures++;continue;}
                }
                bin.recipe=recipe;bin.secondary=secondary;bin.allowed=Set.copyOf(possible);
                bin.min=lo;bin.max=hi;bin.fit=fitted;
                bin.strategy=pass==0?"natural":pass==1?"fitted-envelope":"isolated-lowland";
                long allocated=Math.min(remaining,bin.area-bin.used);bin.used+=allocated;remaining-=allocated;
                if(remaining==0)break;
            }
            if(remaining>0)throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"terrain-capacity",
                "no location can satisfy template intersection, height envelope and remaining interior capacity",
                Map.of("request",request.id,"biome",request.biome,"missing_area",remaining,"interior_regions",bins.size(),
                       "template_rejections",incompatible,"height_rejections",heightFailures,"allowed_templates",allowed(config,request.biome),"seed",seed));
        }
        return new TerrainCapacityPlan(bins.values().stream().filter(b->b.used>0).map(b->new Reservation(
            b.key.x(),b.key.z(),b.recipe.planningCategory(),b.min,b.max,b.used,b.recipe,b.secondary,b.allowed,b.fit.base,b.fit.amplitude,b.strategy)).toList(),ranges);
    }
    private static boolean acceptsEnvelope(Fit fit,Double min,Double max) {
        double low=64+fit.base-.05*fit.amplitude,high=64+fit.base+fit.maximumShape*fit.amplitude;
        double factor=erosionFactor(high);
        return low>=64&&high+8*factor<=318&&(min==null||low-12*factor>=min)&&(max==null||high+8*factor<=max);
    }
    private static Fit fit(double amplitude,Double min,Double max) {
        return fit(amplitude,min,max,1.05);
    }
    private static Fit fit(double amplitude,Double min,Double max,double maximumShape) {
        var natural=new Fit(22,amplitude,maximumShape);if(acceptsEnvelope(natural,min,max))return natural;
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
    private static Set<String> allowed(AdventureWorldConfig c,ContentId id) {
        var rule=c.biomes().terrainRules().get(id);var result=new TreeSet<>(c.world().terrain().enabled());
        if(rule!=null)result.retainAll(rule.effectiveTemplates());return Set.copyOf(result);
    }
    private static TerrainTemplate representative(RegionTerrain.Template t) {
        return switch(t){case PLAINS->TerrainTemplate.PLAINS;case HILLS->TerrainTemplate.HILLS_1;case PLATEAU->TerrainTemplate.PLATEAU;case MOUNTAINS->TerrainTemplate.MOUNTAINS_1;};
    }
    private static Double maxNullable(Double a,Double b){if(a==null)return b;if(b==null)return a;return Math.max(a,b);}
    private static Double minNullable(Double a,Double b){if(a==null)return b;if(b==null)return a;return Math.min(a,b);}
}
