package io.github.luoyan.adventureworldgen.terrain;

/** Spatially correlated selection threshold: coherent islands, with a wandering broad transition.
 * This is not independent per-block dithering, nor a coordinate offset of one contour. */
public final class EcotoneNoise {
    private final ValueNoise patches, clusters, meander;
    public EcotoneNoise(long seed, String id, double patchScale) {
        patches = new ValueNoise(seed, id + "/patches", patchScale);
        clusters = new ValueNoise(seed, id + "/clusters", patchScale * 3);
        meander = new ValueNoise(seed, id + "/meander", patchScale * 8);
    }
    public double threshold(double x, double z) {
        return StrictMath.max(0.02, StrictMath.min(0.98, 0.5
                + 0.80 * patches.sample(x, z) + 0.25 * clusters.sample(x, z)
                + 0.25 * meander.sample(x, z)));
    }
}
