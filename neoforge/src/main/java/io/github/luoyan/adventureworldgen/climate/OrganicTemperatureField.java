package io.github.luoyan.adventureworldgen.climate;

import io.github.luoyan.adventureworldgen.noise.GradientNoise;
import java.util.function.DoubleBinaryOperator;

/** Accepted preview v7: immutable, world-aligned climate, independent of biome demand. */
public final class OrganicTemperatureField implements DoubleBinaryOperator {
    public static final String VERSION="organic-temperature-v7";
    public static final double SCALE=450, BEND=450, WARP_SCALE=1150;
    private final GradientNoise warpX,warpZ,detailX,detailZ,climate,secondary;
    private final double bend;

    public OrganicTemperatureField(long seed) { this(seed,SCALE,BEND,WARP_SCALE); }
    /** Parameterized shape for the diagnostic preview; production uses the versioned defaults. */
    public OrganicTemperatureField(long seed,double spacing,double bend,double scale) {
        if(!Double.isFinite(spacing)||!Double.isFinite(bend)||!Double.isFinite(scale)||spacing<=0||bend<0||scale<=0)
            throw new IllegalArgumentException("invalid organic temperature parameters");
        this.bend=bend;
        // Preserve the preview keys: changing their names changes the accepted geography.
        warpX=new GradientNoise(seed,"temperature-preview/organic/warp-x",scale);
        warpZ=new GradientNoise(seed,"temperature-preview/organic/warp-z",scale);
        detailX=new GradientNoise(seed,"temperature-preview/organic/detail-x",scale*.42);
        detailZ=new GradientNoise(seed,"temperature-preview/organic/detail-z",scale*.42);
        climate=new GradientNoise(seed,"temperature-preview/organic/climate",spacing*2.3);
        secondary=new GradientNoise(seed,"temperature-preview/organic/secondary",spacing*1.3);
    }
    @Override public double applyAsDouble(double x,double z) {
        double wx=x+bend*warpX.sample(x,z),wz=z+bend*warpZ.sample(x,z);
        double qx=wx+.22*bend*detailX.sample(wx,wz),qz=wz+.22*bend*detailZ.sample(wx,wz);
        double field=.8*climate.sample(qx*.72,qz*1.2)
                +.2*secondary.sample(.8*qx-.6*qz,.6*qx+.8*qz);
        return 5+4.5*Math.tanh(3.2*field);
    }
    public static double cooling(double effectiveHeight) {
        double rise=Math.max(0,effectiveHeight-76);
        return .012*rise+(.065-.012)*Math.max(0,rise+76-110);
    }
    public double temperature(double x,double z,double effectiveHeight) {
        return Math.clamp(5+.6*(applyAsDouble(x,z)-5)+2.0-cooling(effectiveHeight),0,10);
    }
}
