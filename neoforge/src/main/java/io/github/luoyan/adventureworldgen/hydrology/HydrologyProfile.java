package io.github.luoyan.adventureworldgen.hydrology;

/**
 * Defaults retained from FreeTerraForged commit {@code 43fd42a4d31da5ba36f5ab4d8e4d76d69688e86a},
 * with project-space units.
 *
 * <p>That commit is the <em>upstream reference identity</em> for the transplanted parameters. It is
 * not this project's hydrology version: the version that participates in plan identity and the
 * READY gate is {@link io.github.luoyan.adventureworldgen.plan.PlanVersions#HYDROLOGY}. The full
 * hash is written out here so it can be copied straight from the source instead of being expanded
 * from the short form; the same hash appears in {@code README.md}, {@code
 * docs/参考资料/FTF-派生文件清单.md} and {@code src/main/resources/META-INF/NOTICE}, which carries
 * the upstream MIT notice.
 */
public record HydrologyProfile(int mainRiverCount, int maximumForkDepth,
                               RiverShape main, RiverShape branch,
                               Lake lake, Wetland wetland, Erosion erosion, Smoothing smoothing) {
    public static final HydrologyProfile FTF_ADAPTED_V1 = new HydrologyProfile(
            8, 3,
            new RiverShape(5, 2, 6, 20, 8, 0.75),
            new RiverShape(4, 1, 4, 14, 5, 0.975),
            new Lake(0.3, 10, 75, 150),
            new Wetland(0.6, 175, 225),
            new Erosion(135, 12, 0.7, 0.7, 0.5, 0.5),
            Smoothing.SUPPORTED);

    /** Wider, longer catchments with sparse first-order tributaries and standing water. */
    public static final HydrologyProfile FINITE_CONTINENT = new HydrologyProfile(
            6, 1,
            new RiverShape(6, 2, 6, 24, 14, 0.75),
            new RiverShape(4, 2, 5, 18, 8, 0.975),
            new Lake(0.18, 4, 30, 65),
            new Wetland(0.12, 24, 44), FTF_ADAPTED_V1.erosion(), FTF_ADAPTED_V1.smoothing());

    public int mainRiverCount(double radius) {
        return this.equals(FINITE_CONTINENT)
                ? Math.max(2, (int)Math.round(mainRiverCount * Math.min(1, Math.sqrt(radius / 3000))))
                : mainRiverCount;
    }

    public record RiverShape(int bedDepth, int minimumBankHeight, int maximumBankHeight,
                             int bankWidth, int bedWidth, double fade) {}

    /**
     * Lake parameters that the generator actually reads.
     *
     * <p>This record used to carry four more components - the start-distance pair and the
     * bank-height pair - that no runtime code ever read. They were not knobs: changing them changed
     * nothing, which made the profile look configurable where it was not. They now live in
     * {@link LakeReference} as the transplant reference they always were.
     */
    public record Lake(double chance, int depth, int minimumSize, int maximumSize) {
        public Lake {
            if (!(chance >= 0) || chance > 1)
                throw new IllegalArgumentException("lake chance must be in [0,1], got " + chance);
            if (depth < 0) throw new IllegalArgumentException("lake depth must not be negative, got " + depth);
            if (minimumSize < 1 || maximumSize < minimumSize)
                throw new IllegalArgumentException("lake size range must be positive and ordered, got "
                        + minimumSize + ".." + maximumSize);
        }
    }

    /**
     * The FreeTerraForged transplant vector for lake placement: the upstream start-distance and
     * bank-height settings carried over with the port.
     *
     * <p><strong>Reference only.</strong> No runtime code reads these values, and changing them
     * cannot change a generated world. They are kept so the upstream settings stay recorded next to
     * the profile they were transplanted from, and so a future implementation that does consume
     * them has an unambiguous starting point - at which point they must move into the effective
     * profile, produce an explainable output change and enter the identity and frozen data.
     */
    public record LakeReference(double minimumStartDistance, double maximumStartDistance,
                                int minimumBankHeight, int maximumBankHeight) {}

    /** Transplant reference carried with {@link #FTF_ADAPTED_V1}. Not a runtime parameter. */
    public static final LakeReference FTF_ADAPTED_V1_LAKE_REFERENCE = new LakeReference(0.0, 0.03, 2, 10);
    /** Transplant reference carried with {@link #FINITE_CONTINENT}. Not a runtime parameter. */
    public static final LakeReference FINITE_CONTINENT_LAKE_REFERENCE = new LakeReference(0.0, 0.03, 2, 6);

    public record Wetland(double chance, int minimumSize, int maximumSize) {}
    public record Erosion(int dropletsPerChunk, int lifetime, double volume, double velocity,
                          double erosionRate, double depositRate) {}

    /**
     * The erosion smoothing kernel: full-height, one snapshot pass.
     *
     * <p>This record is the <em>single source</em> for the smoothing constants -
     * {@code ErodedTerrain} reads {@link #SUPPORTED} instead of repeating 1.8 and 0.9 inline, and
     * construction rejects any triple other than the implemented one. That rejection is the point:
     * the previous shape accepted arbitrary iterations/radius/rate and ignored them, so a
     * non-default value would have looked applied while producing identical output. Supporting more
     * passes is a behaviour change that needs its own iteration snapshots in the frozen data.
     */
    public record Smoothing(int iterations, double radius, double rate) {
        /** The only implemented smoothing: one pass, upstream 1.8-block kernel, rate 0.9. */
        public static final int SUPPORTED_ITERATIONS = 1;
        public static final double SUPPORTED_RADIUS = 1.8;
        public static final double SUPPORTED_RATE = 0.9;
        /** The implemented smoothing, and the only value this record accepts. */
        public static final Smoothing SUPPORTED = new Smoothing(SUPPORTED_ITERATIONS, SUPPORTED_RADIUS, SUPPORTED_RATE);

        public Smoothing {
            if (iterations != SUPPORTED_ITERATIONS || radius != SUPPORTED_RADIUS || rate != SUPPORTED_RATE)
                throw new IllegalArgumentException("only the implemented smoothing is supported "
                        + "(iterations=" + SUPPORTED_ITERATIONS + ", radius=" + SUPPORTED_RADIUS
                        + ", rate=" + SUPPORTED_RATE + "); got iterations=" + iterations
                        + ", radius=" + radius + ", rate=" + rate);
        }
    }
}
