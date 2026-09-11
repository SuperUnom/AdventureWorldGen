package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.noise.GradientNoise;

/**
 * Historical reference implementation: the pre-r21 mountain field, adapted from FTF
 * {@code Populators.makeMountains} / {@code PerlinRidge} at the fixed upstream commit
 * {@code 43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a}. Project seed and hash semantics, not an
 * upstream bit-for-bit port; see {@code META-INF/NOTICE}.
 *
 * <p><strong>Reference only, and the test source set is its only home.</strong> Production mountain
 * height comes from {@link TerrainRecipes}' {@code MOUNTAINS_*}/{@code VOLCANO} recipes, the
 * {@link MountainRangePlan} envelope and the region composition in {@link RegionTerrain}. Nothing
 * in production ever read this class, so instead of leaving a dead class in the main source set it
 * moved here as a named historical comparison - the coverage that matters for production was
 * migrated to {@link TerrainRecipesTest} and does not read this class.
 */
public final class MountainTerrain {
    private final GradientNoise[] ridge = new GradientNoise[4];
    private final GradientNoise warpX, warpZ, detail, envelope;

    public MountainTerrain(long seed) {
        for (int i = 0; i < ridge.length; i++)
            ridge[i] = new GradientNoise(seed, "mountain/ridge/" + i, 610 / StrictMath.pow(2.35, i));
        warpX = new GradientNoise(seed, "mountain/warp/x", 350);
        warpZ = new GradientNoise(seed, "mountain/warp/z", 350);
        detail = new GradientNoise(seed, "mountain/detail", 24);
        envelope = new GradientNoise(seed, "mountain/envelope", 900);
    }

    public double sample(double x, double z) {
        double wx = x + 75 * warpX.sample(x, z), wz = z + 75 * warpZ.sample(x, z);
        double value = 0, normalization = 0, spectral = 1, feedback = 1, gain = 2;
        for (GradientNoise octave : ridge) {
            double signal = 1 - StrictMath.abs(octave.sample(wx, wz));
            signal = signal * signal * feedback;
            feedback = StrictMath.max(0, StrictMath.min(1, signal * gain));
            value += signal * spectral; normalization += spectral;
            spectral /= 2.35; gain *= 1.15;
        }
        double ridges = value / normalization;
        // Broad modulation forms ranges, while the ridge graph supplies peaks and branching spurs.
        double range = 0.5 + 0.5 * envelope.sample(wx, wz);
        return 190 * ridges * ridges * (0.35 + 0.65 * range)
                * (0.9625 + 0.0375 * detail.sample(wx, wz));
    }
}
