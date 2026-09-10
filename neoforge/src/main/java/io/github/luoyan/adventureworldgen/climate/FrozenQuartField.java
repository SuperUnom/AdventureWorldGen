package io.github.luoyan.adventureworldgen.climate;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/** Lazy immutable quart-center values. Continuous off-grid queries keep their exact coordinates. */
final class FrozenQuartField {
    private static final int SIDE=32;
    private final double extent;
    private final ConcurrentHashMap<Long,double[]> tiles=new ConcurrentHashMap<>();
    FrozenQuartField(double extent) { this.extent=extent; }
    double get(double x,double z,DoubleSupplier calculate) {
        if(Math.abs(x)>extent||Math.abs(z)>extent||(x-2)%4!=0||(z-2)%4!=0)
            return calculate.getAsDouble();
        int qx=(int)((x-2)/4),qz=(int)((z-2)/4);
        long key=((long)Math.floorDiv(qx,SIDE)<<32)^(Math.floorDiv(qz,SIDE)&0xffffffffL);
        double[] tile=tiles.computeIfAbsent(key,ignored->{
            double[] values=new double[SIDE*SIDE];Arrays.fill(values,Double.NaN);return values;
        });
        int index=Math.floorMod(qz,SIDE)*SIDE+Math.floorMod(qx,SIDE);
        synchronized(tile) {
            if(Double.isNaN(tile[index]))tile[index]=calculate.getAsDouble();
            return tile[index];
        }
    }
}
