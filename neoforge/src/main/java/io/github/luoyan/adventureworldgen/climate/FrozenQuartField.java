package io.github.luoyan.adventureworldgen.climate;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * Lazy immutable quart-center values, keyed by the quart cell rather than by the exact coordinate.
 *
 * <p>Only coordinates this field may cache are memoised: a quart center inside the frozen extent.
 * Continuous off-grid queries keep their exact coordinates and are computed every time - they have
 * no cell to share a value with.
 *
 * <p>Because one cell holds one value, the value must not depend on which query reached the cell
 * first. The caller therefore decides what to compute ({@link ClimatePlan} re-derives the sample
 * from the frozen terrain), and this class only guarantees the compute-once contract.
 */
final class FrozenQuartField {
    private static final int SIDE=32;
    /** Quart spacing: centers sit at {@code 4k + 2}, which keeps cell boundaries on 4k. */
    static final int QUART=4;
    static final int CENTER_OFFSET=2;
    private final double extent;
    private final ConcurrentHashMap<Long,double[]> tiles=new ConcurrentHashMap<>();
    FrozenQuartField(double extent) { this.extent=extent; }

    /**
     * Whether {@code (x, z)} is a quart center this field memoises. Off-grid coordinates, and
     * coordinates outside the frozen extent, are answered exactly and are never stored.
     */
    boolean cacheable(double x,double z) {
        return x==Math.rint(x)&&z==Math.rint(z)&&Math.abs(x)<=extent && Math.abs(z)<=extent
                && Math.floorMod((long) x - CENTER_OFFSET, QUART)==0
                && Math.floorMod((long) z - CENTER_OFFSET, QUART)==0;
    }

    double get(double x,double z,DoubleSupplier calculate) {
        if(!cacheable(x,z)) return calculate.getAsDouble();
        int qx=(int)((x-CENTER_OFFSET)/QUART),qz=(int)((z-CENTER_OFFSET)/QUART);
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
