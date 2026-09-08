package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.planner.DeterministicRandom;

/** Seeded gradient noise with quintic interpolation, for warped terrain function graphs. */
public final class GradientNoise {
    private final long seed;
    private final double scale, offsetX, offsetZ;

    public GradientNoise(long seed, String field, double scale) {
        this.seed = DeterministicRandom.seed(seed, "terrain-r5", "gradient", field, 0);
        this.scale = scale;
        offsetX = (this.seed & 0xffff) / 65536.0 + 0.37;
        offsetZ = ((this.seed >>> 16) & 0xffff) / 65536.0 + 0.61;
    }

    public double sample(double x, double z) {
        x = x / scale + offsetX; z = z / scale + offsetZ;
        long ix = (long) Math.floor(x), iz = (long) Math.floor(z);
        double tx = x - ix, tz = z - iz, u = fade(tx), v = fade(tz);
        double a = lerp(dot(ix, iz, tx, tz), dot(ix + 1, iz, tx - 1, tz), u);
        double b = lerp(dot(ix, iz + 1, tx, tz - 1), dot(ix + 1, iz + 1, tx - 1, tz - 1), u);
        return StrictMath.max(-1, StrictMath.min(1, lerp(a, b, v) * 1.42));
    }

    private double dot(long x, long z, double dx, double dz) {
        long h = seed ^ (x * 0x9e3779b97f4a7c15L) ^ (z * 0xc2b2ae3d27d4eb4fL);
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        int direction = (int) (h ^ (h >>> 31)) & 7;
        return switch (direction) {
            case 0 -> dx; case 1 -> -dx; case 2 -> dz; case 3 -> -dz;
            case 4 -> (dx + dz) * 0.7071067811865476;
            case 5 -> (dx - dz) * 0.7071067811865476;
            case 6 -> (-dx + dz) * 0.7071067811865476;
            default -> (-dx - dz) * 0.7071067811865476;
        };
    }
    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
