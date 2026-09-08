package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.terrain.ValueNoise;

/** Smooth anisotropic catchment preference. Connectivity comes from the legal-cell frontier. */
public final class OrganicGrowth {
    private final ValueNoise warpX,warpZ,lobes;
    private final double size,ax,az,wx,wz,cos,sin,height;
    public OrganicGrowth(long seed,String id,int x,int z,long area,double height) {
        size=StrictMath.sqrt(area/StrictMath.PI);ax=x;az=z;this.height=height;
        warpX=new ValueNoise(seed,id+"/shape-x",StrictMath.max(64,size*1.6));
        warpZ=new ValueNoise(seed,id+"/shape-z",StrictMath.max(64,size*1.6));
        lobes=new ValueNoise(seed,id+"/shape-lobes",StrictMath.max(48,size*0.8));
        wx=warpX.sample(x,z);wz=warpZ.sample(x,z);
        double angle=(PlacementIndex.mix(seed^id.hashCode())>>>11)*0x1.0p-53*StrictMath.PI;
        cos=StrictMath.cos(angle);sin=StrictMath.sin(angle);
    }
    public double score(int x,int z,double ground) {
        double dx=x-ax+size*0.65*(warpX.sample(x,z)-wx);
        double dz=z-az+size*0.65*(warpZ.sample(x,z)-wz);
        double along=dx*cos+dz*sin,across=-dx*sin+dz*cos;
        return StrictMath.hypot(along*0.72,across*1.38)
                + size*0.22*lobes.sample(x,z) + 0.18*StrictMath.abs(ground-height);
    }
}
