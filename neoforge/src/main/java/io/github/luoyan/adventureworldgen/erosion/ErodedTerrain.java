package io.github.luoyan.adventureworldgen.erosion;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.hydrology.HydrologyProfile;

/** Frozen erosion followed by FTF's block-radius full-height smoothing, before hydrology.
 * Queries never re-run droplets. Cache contents have no effect on the generated heights.
 */
public final class ErodedTerrain implements MacroTerrain {
    private final MacroTerrain base;
    private final ErosionDeltaField delta;
    private final String version;
    // Sparse exact block tiles avoid coordinate-object hash collisions and keep the
    // raw stencil working set alongside the correction tiles. Each cache is bounded
    // to 64 MiB of doubles; eviction only causes deterministic recomputation.
    private final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<double[]> rawHeights =
            new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
    private final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<double[]> filterTiles =
            new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();

    private record CachedTile(long key,double[] values) {}
    private final ThreadLocal<CachedTile> lastRaw=new ThreadLocal<>(),lastFilter=new ThreadLocal<>();
    private double[] tile(it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<double[]> cache, int x, int z) {
        long key=((long)Math.floorDiv(x,16)<<32)^(Math.floorDiv(z,16)&0xffffffffL);
        var local=cache==rawHeights?lastRaw:lastFilter;
        var previous=local.get();
        if(previous!=null&&previous.key==key)return previous.values;
        synchronized(cache) {
            double[] values=cache.getAndMoveToLast(key);
            if(values==null) {
                values=new double[256];java.util.Arrays.fill(values,Double.NaN);
                if(cache.size()>=32768)cache.removeFirst();
                cache.put(key,values);
            }
            local.set(new CachedTile(key,values));
            return values;
        }
    }

    public ErodedTerrain(MacroTerrain base, ErosionDeltaField delta, String version) {
        this.base = base; this.delta = delta; this.version = version;
    }

    @Override public MacroSample sample(double x, double z) {
        MacroSample sample = base.sample(x, z);
        if (sample.wet()) return sample;
        double coastal=coastal(sample.groundSurface());
        double ground=sample.groundSurface()+delta.sample(x,z)*coastal;
        int ix=(int)Math.floor(x),iz=(int)Math.floor(z);
        double tx=x-ix,tz=z-iz;
        if(tx==0&&tz==0) {var raw=tile(rawHeights,ix,iz);synchronized(raw){raw[(ix&15)*16+(iz&15)]=ground;}}
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
        double[] tile=tile(filterTiles,x,z);
        int index=(x&15)*16+(z&15);
        synchronized(tile){if(!Double.isNaN(tile[index]))return tile[index];}
        double ground=rawHeight(x,z);
        // Smoothing.java's inverse elevation modifier retains high mountain relief.
        double modifier=1-StrictMath.max(0,StrictMath.min(1,(ground-65)/119));
        double correction=0;
        if(modifier>0) {
            // The kernel constants come from HydrologyProfile.Smoothing, which is the single source
            // for them and rejects anything other than the implemented pass. They used to be
            // repeated here as literals, so a profile carrying different values would have been
            // accepted by the profile and ignored by the kernel.
            double radius=HydrologyProfile.Smoothing.SUPPORTED.radius();
            double rate=HydrologyProfile.Smoothing.SUPPORTED.rate();
            // Radius is measured in BLOCKS: the persisted erosion grid spacing must not enlarge it.
            double sum=ground,weights=1;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                if(dx==0&&dz==0)continue;
                double weight=1-(dx*dx+dz*dz)/(radius*radius);
                sum+=weight*rawHeight(x+dx,z+dz);weights+=weight;
            }
            correction=(sum/weights-ground)*rate*modifier;
        }
        synchronized(tile){tile[index]=correction;}
        return correction;
    }

    private double rawHeight(int x,int z) {
        double[] raw=tile(rawHeights,x,z);
        int index=(x&15)*16+(z&15);
        synchronized(raw){if(!Double.isNaN(raw[index]))return raw[index];}
        var sample=base.sample(x,z);
        double height=sample.groundSurface()+(sample.wet()?0:delta.sample(x,z)*coastal(sample.groundSurface()));
        synchronized(raw){raw[index]=height;}
        return height;
    }

    private static double coastal(double height) {
        double a=StrictMath.max(0,StrictMath.min(1,(height-64)/16));return a*a*(3-2*a);
    }
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
}
