package io.github.luoyan.adventureworldgen.climate;

import io.github.luoyan.adventureworldgen.api.MacroSample;

/**
 * Read-only view of an accepted temperature field: the band a coordinate falls in, and the
 * unthresholded value the band was derived from.
 *
 * <p>Demand statistics read the field through this interface, so they can be computed and verified
 * on synthetic fields without a planner, and the field never has to know who consumes it.
 */
public interface ClimateField {
    /** Band ordinal of the accepted field at a coordinate. */
    int band(int x, int z, MacroSample sample);

    /** Unthresholded temperature value used to spread demand across bands. */
    double value(int x, int z, MacroSample sample);

    /** One dry sampled site of the field: the same list the field was built from, same order. */
    record Site(int x, int z, MacroSample sample) {}
}
