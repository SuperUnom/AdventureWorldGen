package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.spatial.Vec2;

/** Rotated shear composition gives bays within bays without folding the coast over itself.
 * Each shear preserves its transverse coordinate, so its inverse is the opposite displacement.
 * Detail decays more slowly than wavelength (H < 1), maintaining roughness at block scales. */
public final class FractalCoastWarp {
    private static final double[] SCALES = {384, 160, 64, 24, 8};
    private static final double[] AMPLITUDES = {120, 70, 36, 16, 6};
    private final ValueNoise[] noise = new ValueNoise[SCALES.length * 2];
    private final double[] cosine = new double[noise.length], sine = new double[noise.length];
    private final double strength;

    public FractalCoastWarp(long seed, double radius) {
        strength = StrictMath.min(1, radius / 1500);
        for (int i = 0; i < noise.length; i++) {
            noise[i] = new ValueNoise(seed, "coast/fractal-shear/" + i, SCALES[i / 2]);
            double angle = i * 2.399963229728653;
            cosine[i] = StrictMath.cos(angle); sine[i] = StrictMath.sin(angle);
        }
    }

    public Vec2 apply(double x, double z) {
        for (int i = 0; i < noise.length; i++) {
            double across = -sine[i] * x + cosine[i] * z;
            double displacement = strength * AMPLITUDES[i / 2] * noise[i].sample(across, 17.31);
            x += cosine[i] * displacement;
            z += sine[i] * displacement;
        }
        return new Vec2(x, z);
    }
}
