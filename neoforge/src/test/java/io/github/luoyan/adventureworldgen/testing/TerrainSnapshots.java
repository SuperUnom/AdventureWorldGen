package io.github.luoyan.adventureworldgen.testing;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.plan.PlanVersions;

/**
 * The identities a snapshot may carry in a test, named instead of typed inline.
 *
 * <p>Fixtures used to spread {@code terrain-r22}, {@code terrain-r21}, {@code terrain-v2} and
 * {@code test} through the test suite with nothing saying which of them was a production contract
 * and which was a marker someone invented. There are exactly three intents, and a fixture should
 * say which one it is:
 *
 * <ul>
 *   <li>{@link #PRODUCTION_TERRAIN} / {@link #PRODUCTION_EROSION} - the fixture stands in for a
 *       real planned world, so it takes the value from {@link PlanVersions}, which is the same
 *       source the runtime uses. A contract change then reaches these fixtures automatically
 *       instead of leaving a stale literal behind.</li>
 *   <li>{@link #SYNTHETIC} / {@link #SYNTHETIC_TERRAIN} - no production terrain produced this
 *       object. It is a hand-built sample; its version string is a marker, never a claim about
 *       which revision was verified.</li>
 *   <li>{@link #HISTORICAL_SURFACE_TERRAIN_R21} - a regression fixture that deliberately targets an
 *       older terrain revision. Keeping the old value is the point; the name is what stops it being
 *       read as current coverage.</li>
 * </ul>
 *
 * <p>Values are unchanged from what the fixtures already carried, so no canonical plan bytes move.
 */
public final class TerrainSnapshots {
    /** Production terrain identity, from the same contract the runtime reports. */
    public static final String PRODUCTION_TERRAIN = PlanVersions.TERRAIN;
    /** Production erosion identity. */
    public static final String PRODUCTION_EROSION = PlanVersions.EROSION;
    /** Marker for a hand-built sample that no production terrain produced. */
    public static final String SYNTHETIC = "test";
    /** Marker for a synthetic snapshot that still needs a terrain-shaped version string. */
    public static final String SYNTHETIC_TERRAIN = "terrain-v2";
    /**
     * Historical target of the r21 surface handover fixture: the terrain revision the native
     * surface pipeline was validated against, kept so that fixture keeps exercising that revision.
     */
    public static final String HISTORICAL_SURFACE_TERRAIN_R21 = "terrain-r21";

    /** A dry synthetic sample whose version is the synthetic marker. */
    public static MacroSample synthetic(double groundSurface, String category) {
        return synthetic(groundSurface, Double.NaN, WaterKind.NONE, category);
    }

    /** A synthetic sample with an explicit water surface. */
    public static MacroSample synthetic(double groundSurface, double waterSurface, WaterKind kind, String category) {
        return new MacroSample(groundSurface, waterSurface, kind, false, "r", category, SYNTHETIC);
    }

    /** A sample carrying the production terrain identity, as a planned world's queries would. */
    public static MacroSample production(double groundSurface, String regionId, String category) {
        return new MacroSample(groundSurface, Double.NaN, WaterKind.NONE, false, regionId, category,
                PRODUCTION_TERRAIN);
    }

    /** The composite terrain version a plan diagnostics string carries: terrain plus hydrology detail. */
    public static String productionTerrainDetail(String hydrologyVersion) {
        return PRODUCTION_TERRAIN + "+" + hydrologyVersion;
    }

    /** The full diagnostics suffix: terrain, hydrology and erosion identities. */
    public static String productionDiagnostics(String hydrologyVersion) {
        return PRODUCTION_TERRAIN + "+" + hydrologyVersion + "+" + PRODUCTION_EROSION;
    }

    private TerrainSnapshots() {}
}
