package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.api.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only measurements of the final eroded/carved height field. No smoothing of actual height. */
public final class TerrainMorphology implements MacroTerrain {
    private final MacroTerrain finalSurface;
    private final Map<Key,Metrics> measurements=new ConcurrentHashMap<>();
    private record Key(int x,int z) {}
    private record Metrics(double slope,double relief,double relative) {}
    public TerrainMorphology(MacroTerrain finalSurface) {this.finalSurface=finalSurface;}
    @Override public MacroSample sample(double x,double z) {
        var sample=finalSurface.sample(x,z);
        if(sample.wet()||sample.mountainInfluence()<.15)return sample;
        int gx=(int)Math.floor(x/32),gz=(int)Math.floor(z/32);
        double tx=x/32-gx,tz=z/32-gz;
        var a=at(gx,gz);var b=at(gx+1,gz);var c=at(gx,gz+1);var d=at(gx+1,gz+1);
        return sample.withMorphology(interpolate(a.slope,b.slope,c.slope,d.slope,tx,tz),
            interpolate(a.relief,b.relief,c.relief,d.relief,tx,tz),
            interpolate(a.relative,b.relative,c.relative,d.relative,tx,tz));
    }
    private Metrics at(int x,int z) {return measurements.computeIfAbsent(new Key(x,z),this::measure);}
    private Metrics measure(Key key) {
        double x=key.x*32.0,z=key.z*32.0,h=finalSurface.sample(x,z).groundSurface();
        double dx=(finalSurface.sample(x+8,z).groundSurface()-finalSurface.sample(x-8,z).groundSurface())/16;
        double dz=(finalSurface.sample(x,z+8).groundSurface()-finalSurface.sample(x,z-8).groundSurface())/16;
        double low=h,high=h,total=0;
        for(int ox=-1;ox<=1;ox++)for(int oz=-1;oz<=1;oz++) {
            if(ox==0&&oz==0)continue;
            double v=finalSurface.sample(x+ox*64,z+oz*64).groundSurface();
            low=Math.min(low,v);high=Math.max(high,v);total+=v;
        }
        return new Metrics(StrictMath.hypot(dx,dz),high-low,h-total/8);
    }
    private static double interpolate(double a,double b,double c,double d,double x,double z) {
        return TerrainRecipes.lerp(TerrainRecipes.lerp(a,b,x),TerrainRecipes.lerp(c,d,x),z);
    }
}
