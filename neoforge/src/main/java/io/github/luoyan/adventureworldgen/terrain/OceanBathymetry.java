package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.noise.ValueNoise;

/** Shared shelf/slope/deep-basin height field for planning and Minecraft columns. */
public final class OceanBathymetry {
    private final ValueNoise basins, hills, detail, shelfWidth;
    public OceanBathymetry(long seed) {
        basins=new ValueNoise(seed,"ocean/basins",420);
        hills=new ValueNoise(seed,"ocean/hills",110);
        detail=new ValueNoise(seed,"ocean/detail",28);
        shelfWidth=new ValueNoise(seed,"ocean/shelf-width",640);
    }
    public static double extent(double seaBand) { return seaBand*3.5; }
    public double depth(double x,double z,double offshore,double seaBand) {
        // Broadly varying shelf widths avoid perfectly concentric depth contours.
        double d=Math.max(0,offshore)*256/seaBand/(1+.18*shelfWidth.sample(x,z));
        double terraces=8*ramp(d,0,48)+12*ramp(d,80,176)
                +18*ramp(d,224,384)+24*ramp(d,448,704);
        double relief=(12*basins.sample(x,z)+5*hills.sample(x,z)+1.5*detail.sample(x,z))
                *ramp(d,32,400);
        return Math.max(0,terraces+relief);
    }
    private static double ramp(double v,double low,double high) {
        double t=Math.max(0,Math.min(1,(v-low)/(high-low)));
        return t*t*(3-2*t);
    }
}
