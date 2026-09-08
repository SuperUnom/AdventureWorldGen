package io.github.luoyan.adventureworldgen.erosion;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;

/** Frozen erosion followed by FTF's block-radius full-height smoothing, before hydrology.
 * Queries never re-run droplets. Cache contents have no effect on the generated heights.
 */
public final class ErodedTerrain implements MacroTerrain {
    private final MacroTerrain base;
    private final ErosionDeltaField delta;
    private final String version;
    private record Point(int x,int z) {}
    private final java.util.Map<Point,Double> rawHeights = new java.util.LinkedHashMap<>(1024,.75f,true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Point,Double> eldest) {
            return size()>65536;
        }
    };
    // Exact block-grid filter corrections, at most 64 MiB per plan. Fractional queries
    // interpolate the correction, retaining the continuous original recipe underneath.
    private final java.util.Map<Long,double[]> filterTiles = new java.util.LinkedHashMap<>(128,.75f,true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long,double[]> eldest) {
            return size()>8192;
        }
    };

    public ErodedTerrain(MacroTerrain base, ErosionDeltaField delta, String version) {
        this.base = base; this.delta = delta; this.version = version;
    }

    @Override public MacroSample sample(double x, double z) {
        MacroSample sample = base.sample(x, z);
        if (sample.wet()) return sample;
        double coastal=coastal(sample.groundSurface());
        double ground=sample.groundSurface()+delta.sample(x,z)*coastal;
        int ix=(int)StrictMath.floor(x),iz=(int)StrictMath.floor(z);
        double tx=x-ix,tz=z-iz;
        if(tx==0&&tz==0)synchronized(rawHeights){rawHeights.put(new Point(ix,iz),ground);}
        double correction=filter(ix,iz);
        if(tx!=0)correction=lerp(correction,filter(ix+1,iz),tx);
        if(tz!=0) {
            double other=filter(ix,iz+1);
            if(tx!=0)other=lerp(other,filter(ix+1,iz+1),tx);
            correction=lerp(correction,other,tz);
        }
        return sample.withSurface(ground+coastal*correction,sample.waterSurface(),sample.waterKind(),sample.terrainVersion()+"+"+version);
    }

    private double filter(int x,int z) {
        long key=((long)Math.floorDiv(x,32)<<32)^(Math.floorDiv(z,32)&0xffffffffL);
        double[] tile;
        synchronized(filterTiles) {
            tile=filterTiles.computeIfAbsent(key,ignored->{double[] values=new double[1024];java.util.Arrays.fill(values,Double.NaN);return values;});
        }
        int index=(x&31)*32+(z&31);
        synchronized(tile){if(!Double.isNaN(tile[index]))return tile[index];}
        double ground=rawHeight(x,z);
        // Smoothing.java's inverse elevation modifier retains high mountain relief.
        double modifier=1-StrictMath.max(0,StrictMath.min(1,(ground-65)/119));
        double correction=0;
        if(modifier>0) {
            // Radius 1.8 BLOCKS, quadratic radial weights, rate 0.9, one snapshot pass.
            // The persisted erosion grid spacing must not enlarge this physical radius.
            double sum=ground,weights=1;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                if(dx==0&&dz==0)continue;
                double weight=1-(dx*dx+dz*dz)/(1.8*1.8);
                sum+=weight*rawHeight(x+dx,z+dz);weights+=weight;
            }
            correction=(sum/weights-ground)*.9*modifier;
        }
        synchronized(tile){tile[index]=correction;}
        return correction;
    }

    private double rawHeight(int x,int z) {
        var point=new Point(x,z);
        synchronized(rawHeights){var height=rawHeights.get(point);if(height!=null)return height;}
        var sample=base.sample(x,z);
        double height=sample.groundSurface()+(sample.wet()?0:delta.sample(x,z)*coastal(sample.groundSurface()));
        synchronized(rawHeights){rawHeights.put(point,height);}
        return height;
    }
    private static double coastal(double height) {
        double a=StrictMath.max(0,StrictMath.min(1,(height-64)/16));return a*a*(3-2*a);
    }
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
}
