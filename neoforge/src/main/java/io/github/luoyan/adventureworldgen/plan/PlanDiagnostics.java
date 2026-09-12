package io.github.luoyan.adventureworldgen.plan;

/**
 * Operation counts recorded when a plan was built, together with the terrain/hydrology/erosion
 * identity they were measured under.
 *
 * <p>Frozen plan data: the storage layer writes these fields and reads them back; nothing in the
 * query path derives behaviour from them.
 */
public record PlanDiagnostics(long coastVertices, long riverChannels, long riverPoints,
                              long erosionSamples, long erosionOperations,
                              long costNodes, long costEdges, long jointOperations,
                              String terrainVersion) {
    /** Counts available before the expensive stages run; every unmeasured stage stays zero. */
    public static PlanDiagnostics basic(long coastVertices, long riverChannels, long riverPoints,
                                        String terrainVersion) {
        return new PlanDiagnostics(coastVertices, riverChannels, riverPoints, 0, 0, 0, 0, 0, terrainVersion);
    }
}
