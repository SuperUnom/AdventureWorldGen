package io.github.luoyan.adventureworldgen.erosion;

import java.util.Arrays;

/** Persistable, world-aligned deterministic height delta grid. */
public final class ErosionDeltaField {
    private final int originX, originZ, spacing, width, height;
    private final float[] deltas;
    private final long operationCount;

    public ErosionDeltaField(int originX, int originZ, int spacing, int width, int height, float[] deltas) {
        this(originX, originZ, spacing, width, height, deltas, 0);
    }

    public ErosionDeltaField(int originX, int originZ, int spacing, int width, int height, float[] deltas,
                             long operationCount) {
        if (spacing <= 0 || width < 2 || height < 2 || deltas.length != Math.multiplyExact(width, height))
            throw new IllegalArgumentException("invalid erosion field dimensions");
        this.originX = originX; this.originZ = originZ; this.spacing = spacing;
        this.width = width; this.height = height; this.deltas = deltas.clone();
        this.operationCount = operationCount;
    }

    public double sample(double x, double z) {
        double gx = (x - originX) / spacing, gz = (z - originZ) / spacing;
        int x0 = clamp((int) Math.floor(gx), 0, width - 2);
        int z0 = clamp((int) Math.floor(gz), 0, height - 2);
        double tx = clamp(gx - x0, 0.0, 1.0), tz = clamp(gz - z0, 0.0, 1.0);
        double a = lerp(get(x0, z0), get(x0 + 1, z0), tx);
        double b = lerp(get(x0, z0 + 1), get(x0 + 1, z0 + 1), tx);
        return lerp(a, b, tz);
    }

    public float get(int x, int z) { return deltas[x * height + z]; }
    public int originX() { return originX; }
    public int originZ() { return originZ; }
    public int spacing() { return spacing; }
    public int width() { return width; }
    public int height() { return height; }
    public float[] copyDeltas() { return deltas.clone(); }
    public long operationCount() { return operationCount; }

    @Override public boolean equals(Object other) {
        return other instanceof ErosionDeltaField field && originX == field.originX && originZ == field.originZ
                && spacing == field.spacing && width == field.width && height == field.height
                && operationCount == field.operationCount && Arrays.equals(deltas, field.deltas);
    }
    @Override public int hashCode() { return 31 * Arrays.hashCode(deltas) + originX + 17 * originZ + Long.hashCode(operationCount); }
    private static int clamp(int v, int lo, int hi) { return StrictMath.max(lo, StrictMath.min(hi, v)); }
    private static double clamp(double v, double lo, double hi) { return StrictMath.max(lo, StrictMath.min(hi, v)); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
