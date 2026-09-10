package io.github.luoyan.adventureworldgen.biome;

import io.github.luoyan.adventureworldgen.plan.ContentId;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import io.github.luoyan.adventureworldgen.noise.ValueNoise;

/** Smooth bounded domain warp for biome ownership. The callback must read raw ownership,
 * never this mixed result. Unlike a full-radius random direction at every query, the two
 * correlated octaves keep neighbouring quart samples on the same continuous contour. */
public final class LocalBiomeBlend {
    private static final double INV_SQRT_TWO=1/Math.sqrt(2);
    private final int radius;
    private final ValueNoise coarseX,coarseZ,detailX,detailZ;
    public LocalBiomeBlend(long seed){this(seed,4);}
    public LocalBiomeBlend(long seed,int radius) {
        if(radius<0||radius>32)throw new IllegalArgumentException("blend radius outside [0,32]");
        this.radius=radius;
        double coarseScale=Math.max(96,radius*8),detailScale=Math.max(32,radius*3);
        coarseX=new ValueNoise(seed,"query-blend/coarse-x",coarseScale);
        coarseZ=new ValueNoise(seed,"query-blend/coarse-z",coarseScale);
        detailX=new ValueNoise(seed,"query-blend/detail-x",detailScale);
        detailZ=new ValueNoise(seed,"query-blend/detail-z",detailScale);
    }
    public ContentId sample(int x,int z,BiFunction<Integer,Integer,ContentId> raw,
                            Predicate<ContentId> legal,ContentId original) {
        if(radius==0)return original;
        // TerraForged-style domain warp: broad bending plus weaker edge detail. Each component
        // remains in [-radius/sqrt(2), radius/sqrt(2)], so the sampled point stays in the
        // configured Euclidean radius without normalising every query to the outer circle.
        double dx=.75*coarseX.sample(x,z)+.25*detailX.sample(x,z);
        double dz=.75*coarseZ.sample(x,z)+.25*detailZ.sample(x,z);
        int ox=(int)Math.round(radius*INV_SQRT_TWO*dx);
        int oz=(int)Math.round(radius*INV_SQRT_TWO*dz);
        while(ox*ox+oz*oz>radius*radius){if(Math.abs(ox)>=Math.abs(oz))ox-=Integer.signum(ox);else oz-=Integer.signum(oz);}
        ContentId candidate=raw.apply(x+ox,z+oz);
        return legal.test(candidate)?candidate:original;
    }
}
