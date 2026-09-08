package io.github.luoyan.adventureworldgen.terrain;

import java.util.*;
import io.github.luoyan.adventureworldgen.planner.DeterministicRandom;
import io.github.luoyan.adventureworldgen.spatial.Vec2;

/** Frozen meandering range corridors planned before terrain, erosion, rivers and biome ownership. */
public record MountainRangePlan(List<Range> ranges) {
    public MountainRangePlan { ranges=List.copyOf(ranges); }
    public record Range(String id,List<Vec2> spine,double width) {
        public Range {
            spine=List.copyOf(spine);
            if(spine.size()<2||!Double.isFinite(width)||width<=0||spine.stream().anyMatch(p->!Double.isFinite(p.x())||!Double.isFinite(p.z())))
                throw new IllegalArgumentException("invalid mountain range");
        }
    }
    public static MountainRangePlan empty() { return new MountainRangePlan(List.of()); }
    public static MountainRangePlan create(long seed,double radius,TerrainSettings settings) {
        if(!settings.mountainRanges()||Arrays.stream(TerrainTemplate.values()).noneMatch(t->t.mountain()&&settings.get(t).weight()>0))return empty();
        var ranges=new ArrayList<Range>();
        int count=Math.max(1,(int)Math.ceil(radius/1700));
        for(int r=0;r<count;r++) {
            String id="range/"+r;
            double angle=sample(seed,id,0)*Math.PI*2,offset=radius*(.2+.3*sample(seed,id,1));
            double length=radius*(.7+.5*sample(seed,id,2));
            var points=new ArrayList<Vec2>();
            for(int i=0;i<=12;i++) {
                double along=(i/12.0-.5)*length;
                double bend=offset+radius*.08*Math.sin(i*.6+sample(seed,id,3)*6);
                points.add(new Vec2(Math.cos(angle)*along-Math.sin(angle)*bend,Math.sin(angle)*along+Math.cos(angle)*bend));
            }
            ranges.add(new Range(id,points,Math.min(650,radius*.22)));
        }
        return new MountainRangePlan(ranges);
    }
    public double influence(double x,double z) {
        double result=0;
        for(var range:ranges)for(int i=1;i<range.spine.size();i++) {
            var a=range.spine.get(i-1);var b=range.spine.get(i);
            double dx=b.x()-a.x(),dz=b.z()-a.z(),square=dx*dx+dz*dz;
            double t=square==0?0:TerrainRecipes.clamp(((x-a.x())*dx+(z-a.z())*dz)/square);
            double px=x-a.x()-t*dx,pz=z-a.z()-t*dz;
            // Outside either axis of the support radius, the clamped contribution is exactly zero.
            if(StrictMath.abs(px)>=range.width||StrictMath.abs(pz)>=range.width)continue;
            double distance=StrictMath.hypot(px,pz);
            result=Math.max(result,TerrainRecipes.smooth(TerrainRecipes.clamp(1-distance/range.width)));
        }
        return result;
    }
    private static double sample(long seed,String id,int i) {return DeterministicRandom.sample(seed,"terrain-r21","mountain-range",id,i);}
}
