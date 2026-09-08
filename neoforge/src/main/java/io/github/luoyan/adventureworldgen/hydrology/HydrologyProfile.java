package io.github.luoyan.adventureworldgen.hydrology;

/** Defaults retained from FreeTerraForged commit 43fd42a, with project-space units. */
public record HydrologyProfile(int mainRiverCount, int maximumForkDepth,
                               RiverShape main, RiverShape branch,
                               Lake lake, Wetland wetland, Erosion erosion, Smoothing smoothing) {
    public static final HydrologyProfile FTF_ADAPTED_V1 = new HydrologyProfile(
            8, 3,
            new RiverShape(5, 2, 6, 20, 8, 0.75),
            new RiverShape(4, 1, 4, 14, 5, 0.975),
            new Lake(0.3, 0.0, 0.03, 10, 75, 150, 2, 10),
            new Wetland(0.6, 175, 225),
            new Erosion(135, 12, 0.7, 0.7, 0.5, 0.5),
            new Smoothing(1, 1.8, 0.9));

    /** Smaller, sparse standing water for the finite 3000-block continent. Upstream golden values remain above. */
    public static final HydrologyProfile FINITE_CONTINENT = new HydrologyProfile(
            8, 3, FTF_ADAPTED_V1.main(), FTF_ADAPTED_V1.branch(),
            new Lake(0.18, 0.0, 0.03, 4, 30, 65, 2, 6),
            new Wetland(0.12, 24, 44), FTF_ADAPTED_V1.erosion(), FTF_ADAPTED_V1.smoothing());

    public record RiverShape(int bedDepth, int minimumBankHeight, int maximumBankHeight,
                             int bankWidth, int bedWidth, double fade) {}
    public record Lake(double chance, double minimumStartDistance, double maximumStartDistance,
                       int depth, int minimumSize, int maximumSize,
                       int minimumBankHeight, int maximumBankHeight) {}
    public record Wetland(double chance, int minimumSize, int maximumSize) {}
    public record Erosion(int dropletsPerChunk, int lifetime, double volume, double velocity,
                          double erosionRate, double depositRate) {}
    public record Smoothing(int iterations, double radius, double rate) {}
}
