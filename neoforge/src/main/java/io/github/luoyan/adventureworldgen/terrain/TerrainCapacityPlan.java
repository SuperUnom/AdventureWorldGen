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
    private record Request(String id,List<ContentId> biomes,int level,long minimum,long target) {}
    private record Choice(Bin bin,ContentId biome,TerrainTemplate recipe,TerrainTemplate secondary,
                          Set<String> allowed,Double min,Double max,Fit fit,double score) {}
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
        for(var d:expanded.patches())requests.add(new Request(d.patchId(),d.allowedBiomes(),d.adventureLevel(),
                d.area().inCells(4).min()*16,d.area().target()));
        requests.sort(Comparator.comparingInt((Request r)->r.level==0?-1:
                r.biomes.stream().flatMap(id->allowed(config,id).stream()).distinct().toList().size()).thenComparing(Request::id));
        var central=geometry.regionKeyAt(0,0);
        Map<String,ContentId> selected=new HashMap<>();
        Map<String,Long> allocated=new HashMap<>();
        // Commit every minimum first. Targets and erosion/climate headroom use only spare capacity.
        for(int round=0;round<2;round++)for(var request:requests) {
            long desired=round==0?request.minimum:Math.max(request.minimum,
                    (long)Math.ceil(Math.min((double)Long.MAX_VALUE,request.target*1.25)));
            long remaining=desired-allocated.getOrDefault(request.id,0L);
            if(remaining<=0)continue;
            var choices=new ArrayList<Choice>();
            for(var bin:bins.values()) {
                if(bin.used>=bin.area)continue;
                var natural=geometry.region(bin.key.x(),bin.key.z());
                for(var biome:request.biomes) {
                    if(selected.containsKey(request.id)&&!selected.get(request.id).equals(biome))continue;
                    var rule=config.biomes().terrainRules().get(biome);
                    var possible=new TreeSet<>(allowed(config,biome));
                    if(bin.allowed!=null)possible.retainAll(bin.allowed);
                    if(possible.isEmpty())continue;
                    Double lo=maxNullable(bin.min,rule==null?null:rule.minHeight());
                    Double hi=minNullable(bin.max,rule==null?null:rule.maxHeight());
                    var recipe=bin.recipe!=null?bin.recipe:natural.recipe();
                    var secondary=bin.recipe!=null?bin.secondary:natural.secondary();
                    boolean naturalAllowed=possible.contains(recipe.id())&&(secondary==null||possible.contains(secondary.id()));
                    if(!naturalAllowed) {
                        if(bin.used>0)continue;
                        recipe=possible.stream().map(TerrainTemplate::byId)
                                .min(Comparator.comparingDouble((TerrainTemplate t)->settings.get(t).verticalAmplitude())
                                        .thenComparing(TerrainTemplate::id)).orElseThrow();
                        secondary=null;
                    }
                    double amplitude=settings.get(recipe).verticalAmplitude();
                    if(secondary!=null)amplitude=Math.max(amplitude,settings.get(secondary).verticalAmplitude());
                    double shape=settings.maximumShape(recipe,secondary);
                    var fitted=bin.fit!=null?bin.fit:new Fit(natural.baseElevation(),amplitude,shape);
                    boolean naturalFit=naturalAllowed&&acceptsEnvelope(fitted,lo,hi);
                    if(!naturalFit)fitted=fit(amplitude,lo,hi,shape);
                    if(fitted==null)continue;
                    double target=config.world().radius()*request.level/10.0;
                    double score=Math.abs(StrictMath.hypot(bin.x,bin.z)-target)
                            +(naturalFit?0:96)+Math.max(0,remaining-(bin.area-bin.used))/1024.0;
                    if(request.level==0&&bin.key.equals(central))score-=10000;
                    choices.add(new Choice(bin,biome,recipe,secondary,Set.copyOf(possible),lo,hi,fitted,score));
                }
            }
            choices.sort(Comparator.comparingDouble(Choice::score).thenComparingLong(c->c.bin.key.x())
                    .thenComparingLong(c->c.bin.key.z()).thenComparing(Choice::biome));
            // Select one feasible carrier biome, then greedily reserve nearby compatible regions.
            // Test aggregate capacity before committing, so an undersupplied first alternative cannot win.
            if(round==0) {
                Map<ContentId,Long> capacity=new HashMap<>();
                for(var choice:choices)capacity.merge(choice.biome,choice.bin.area-choice.bin.used,Long::sum);
                for(var choice:choices)if(capacity.get(choice.biome)>=remaining) {
                    selected.put(request.id,choice.biome);break;
                }
            }
            for(var choice:choices) {
                if(!choice.biome.equals(selected.get(request.id)))continue;
                var bin=choice.bin;
                bin.recipe=choice.recipe;bin.secondary=choice.secondary;bin.allowed=choice.allowed;
                bin.min=choice.min;bin.max=choice.max;bin.fit=choice.fit;bin.strategy="demand-greedy";
                long amount=Math.min(remaining,bin.area-bin.used);bin.used+=amount;remaining-=amount;
                allocated.merge(request.id,amount,Long::sum);
                if(remaining==0)break;
            }
            if(round==0&&remaining>0)throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"terrain-capacity",
                    "no allowed biome has sufficient template, height envelope and interior capacity",
                    Map.of("request",request.id,"biomes",request.biomes,"missing_area",remaining,
                            "interior_regions",bins.size(),"seed",seed));
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
