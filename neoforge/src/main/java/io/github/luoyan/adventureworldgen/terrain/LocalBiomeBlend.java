package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.config.ContentId;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Block-radius boundary mixing. The callback must read raw ownership, never this mixed result. */
public final class LocalBiomeBlend {
    private final int radius;
    private final ValueNoise xNoise,zNoise;
    public LocalBiomeBlend(long seed){this(seed,4);}
    public LocalBiomeBlend(long seed,int radius) {
        if(radius<0||radius>32)throw new IllegalArgumentException("blend radius outside [0,32]");
        this.radius=radius;xNoise=new ValueNoise(seed,"query-blend/x",5);zNoise=new ValueNoise(seed,"query-blend/z",5);
    }
    public ContentId sample(int x,int z,BiFunction<Integer,Integer,ContentId> raw,
                            Predicate<ContentId> legal,ContentId original) {
        if(radius==0)return original;
        // A continuous displacement whose Euclidean magnitude never exceeds the configured radius.
        // Thus any selected label is actually present within that radius; interiors are unchanged.
        double dx=xNoise.sample(x,z),dz=zNoise.sample(x,z),length=Math.max(1,Math.hypot(dx,dz));
        int ox=(int)Math.round(radius*dx/length),oz=(int)Math.round(radius*dz/length);
        while(ox*ox+oz*oz>radius*radius){if(Math.abs(ox)>=Math.abs(oz))ox-=Integer.signum(ox);else oz-=Integer.signum(oz);}
        ContentId candidate=raw.apply(x+ox,z+oz);
        return legal.test(candidate)?candidate:original;
    }
}
