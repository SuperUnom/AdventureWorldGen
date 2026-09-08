package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.planner.*;
import java.util.*;

/** Macro capacity commitments chosen before erosion/water. Final quart matching supplies the certificate. */
public final class TerrainCapacityPlan {
    public record Reservation(long gridX,long gridZ,RegionTerrain.Template template,
                              Double minHeight,Double maxHeight,long reservedArea) {}
    private final List<Reservation> reservations;
    private final Map<RegionTerrain.GridKey,Reservation> lookup;
    public TerrainCapacityPlan(List<Reservation> reservations) {
        this.reservations=reservations.stream().sorted(Comparator.comparingLong(Reservation::gridX).thenComparingLong(Reservation::gridZ)).toList();
        Map<RegionTerrain.GridKey,Reservation> map=new HashMap<>();
        for(var r:this.reservations) {
            if(r.reservedArea<0 || (r.minHeight!=null&&!Double.isFinite(r.minHeight)) || (r.maxHeight!=null&&!Double.isFinite(r.maxHeight)))
                throw new IllegalArgumentException("invalid terrain capacity reservation");
            if(map.put(new RegionTerrain.GridKey(r.gridX,r.gridZ),r)!=null) throw new IllegalArgumentException("duplicate reserved region");
        }
        lookup=Map.copyOf(map);
    }
    public static TerrainCapacityPlan empty() { return new TerrainCapacityPlan(List.of()); }
    public List<Reservation> reservations() { return reservations; }
    public Reservation at(RegionTerrain.GridKey key) { return lookup.get(key); }
    private record Request(String id,ContentId biome,int level,long area) {}
    private static final class Bin {
        final RegionTerrain.GridKey key;
        long area,used; double x,z;
        RegionTerrain.Template template;
        Double min,max;
        Bin(RegionTerrain.GridKey key) { this.key=key; }
    }

    public static TerrainCapacityPlan reserve(long seed,AdventureWorldConfig config,Coastline coast,double landBand) {
        var geometry=new RegionTerrain(seed,PlannerProfile.V2);
        Map<RegionTerrain.GridKey,Bin> bins=new HashMap<>();
        int extent=(int)StrictMath.ceil(config.world().radius()/32)*32;
        for(int z=-extent;z<extent;z+=32)for(int x=-extent;x<extent;x+=32) {
            if(coast.signedDistance(x+16,z+16)<landBand+24)continue;
            // Eligibility now has a 48-cost ecotone, not the old 220-wide terrain mosaic.
            // Keep a tile-sized safety margin without discarding most usable region interiors.
            var key=geometry.interiorRegionAt(x+16,z+16,144);
            if(key==null)continue;
            var bin=bins.computeIfAbsent(key,Bin::new);
            bin.area+=1024; bin.x+=(x+16)*1024.0; bin.z+=(z+16)*1024.0;
        }
        for(var b:bins.values()) { b.x/=b.area; b.z/=b.area; }
        var expanded=new RequirementExpander().expandMinimum(config);
        List<Request> requests=new ArrayList<>();
        for(var d:expanded.patches())requests.add(new Request(d.patchId(),d.allowedBiomes().getFirst(),d.adventureLevel(),d.area().inCells(4).min()*16));
        for(var d:expanded.structures()) requests.add(new Request(d.instanceId(),d.allowedBiomes().isEmpty()?config.biomes().filler().getFirst():d.allowedBiomes().getFirst(),
                d.adventureLevel(),d.carrierArea().inCells(4).min()*16));
        requests.sort(Comparator.comparingInt((Request r)->r.level==0?-1:allowed(config,r.biome).size()).thenComparing(Request::id));
        var central=geometry.regionKeyAt(0,0);
        for(var request:requests) {
            var rule=config.biomes().terrainRules().get(request.biome);
            Double min=rule==null?null:rule.minHeight(),max=rule==null?null:rule.maxHeight();
            if(min!=null&&max!=null&&max-min<20) throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"terrain-capacity",
                    "height interval narrower than the conservative erosion envelope",Map.of("biome",request.biome,"min_height",min,"max_height",max));
            long remaining=request.area;
            var choices=new ArrayList<>(bins.values());
            choices.sort(Comparator.comparingDouble((Bin b)-> {
                double target=config.world().radius()*request.level/10.0;
                double score=Math.abs(StrictMath.hypot(b.x,b.z)-target);
                if(request.level==0&&b.key.equals(central))score-=10000;
                var natural=geometry.region(b.key.x(),b.key.z()).template();
                if(allowed(config,request.biome).contains(natural.name().toLowerCase()))score-=128;
                return score;
            }).thenComparingLong(b->b.key.x()).thenComparingLong(b->b.key.z()));
            for(var bin:choices) {
                if(bin.used>=bin.area)continue;
                if(bin.template!=null&&!allowed(config,request.biome).contains(bin.template.name().toLowerCase()))continue;
                Double lo=maxNullable(bin.min,min),hi=minNullable(bin.max,max);
                if(lo!=null&&hi!=null&&hi-lo<20)continue;
                if(bin.template==null) {
                    var natural=geometry.region(bin.key.x(),bin.key.z()).template();
                    bin.template=allowed(config,request.biome).contains(natural.name().toLowerCase())?natural:
                            Arrays.stream(RegionTerrain.Template.values()).filter(t->allowed(config,request.biome).contains(t.name().toLowerCase())).findFirst().orElseThrow();
                }
                bin.min=lo; bin.max=hi;
                long allocated=Math.min(remaining,bin.area-bin.used); bin.used+=allocated; remaining-=allocated;
                if(remaining==0)break;
            }
            if(remaining>0) throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"terrain-capacity",
                    "macro interior capacity exhausted before terrain generation",Map.of("request",request.id,"biome",request.biome,
                            "missing_area",remaining,"interior_regions",bins.size(),"seed",seed));
        }
        return new TerrainCapacityPlan(bins.values().stream().filter(b->b.used>0).map(b->new Reservation(b.key.x(),b.key.z(),b.template,b.min,b.max,b.used)).toList());
    }
    private static Set<String> allowed(AdventureWorldConfig c,ContentId id) {
        var rule=c.biomes().terrainRules().get(id); return rule==null?AdventureWorldConfig.TerrainRule.TEMPLATES:rule.allowedTerrain();
    }
    private static Double maxNullable(Double a,Double b) { if(a==null)return b; if(b==null)return a; return Math.max(a,b); }
    private static Double minNullable(Double a,Double b) { if(a==null)return b; if(b==null)return a; return Math.min(a,b); }
}
