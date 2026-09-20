package io.github.luoyan.adventureworldgen.plan;

/**
 * Canonical version identities that shape a persisted plan.
 *
 * <p>These strings are contract names, not revision counters: they participate in
 * {@code runtime.PlanIdentity.hash} and in the current plan header, so a value change
 * invalidates existing READY plans and must be a reviewed format decision. They live here so
 * version information is maintained in one place and the domain packages can depend on it
 * instead of the other way round.
 */
public final class PlanVersions {
    /** Hydrology geometry/morphology version, consumed by the plan header and the input hash. */
    public static final String HYDROLOGY = "ftf-hydrology-adapted-v2";

    /**
     * Terrain identity reported by a frozen plan (the query samples' terrain version and the
     * {@code terrain=} line of the input hash). It names the terrain revision a world was planned
     * with; changing it is a reviewed decision, not a rename.
     */
    public static final String TERRAIN = "terrain-r22";

    /** Erosion identity carried by the composed planning terrain. */
    public static final String EROSION = "erosion-v2";

    /**
     * Random-domain namespace of the terrain recipe streams ({@code TerrainRecipes},
     * {@code MountainRangePlan}).
     *
     * <p>This is deliberately <em>not</em> {@link #TERRAIN}: it is a salt that selects which
     * deterministic stream a recipe draws from, so changing it changes generated terrain while
     * leaving the reported version untouched - exactly the kind of silent divergence a single
     * constant is meant to prevent. The value is frozen; the split exists because the recipe salt
     * predates the r22 detail fix and was never re-derived.
     */
    public static final String TERRAIN_RECIPE_DOMAIN = "terrain-r21";

    private PlanVersions() {}
}
