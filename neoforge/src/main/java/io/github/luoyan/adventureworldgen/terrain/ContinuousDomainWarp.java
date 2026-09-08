package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.spatial.Vec2;

/** Four small invertible displacements instead of one large, folding coordinate offset.
 * ValueNoise has |partial derivative| <= 3 / scale; each step has infinity-norm < 0.49.
 * Consequently each step is injective and orientation-preserving, including the fine detail. */
public final class ContinuousDomainWarp {
    private static final double[] SCALES = {512, 160, 64};
    private static final double[] AMPLITUDES = {64, 12, 8};
    private final ValueNoise[] xNoise = new ValueNoise[3], zNoise = new ValueNoise[3];
    private final double strength;
    public ContinuousDomainWarp(long seed, String id, double strength) {
        if (!Double.isFinite(strength) || strength < 0 || strength > 1) throw new IllegalArgumentException("invalid warp strength");
        this.strength = strength;
        for (int i = 0; i < 3; i++) {
            xNoise[i] = new ValueNoise(seed, id + "/x/" + i, SCALES[i]);
            zNoise[i] = new ValueNoise(seed, id + "/z/" + i, SCALES[i]);
        }
    }
    public Vec2 apply(double x, double z) {
        for (int step = 0; step < 4; step++) {
            double dx = 0, dz = 0;
            for (int layer = 0; layer < 3; layer++) {
                dx += AMPLITUDES[layer] * xNoise[layer].sample(x, z);
                dz += AMPLITUDES[layer] * zNoise[layer].sample(x, z);
            }
            x += dx * strength / 4; z += dz * strength / 4;
        }
        return new Vec2(x, z);
    }
}
