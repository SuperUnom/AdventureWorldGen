package io.github.luoyan.adventureworldgen.climate;

import io.github.luoyan.adventureworldgen.api.MacroSample;

/**
 * Read-only view of an accepted temperature field.
 *
 * <p>Demand statistics read the field through this interface, so they can be computed and verified
 * on synthetic fields without a planner, and the field never has to know who consumes it.
 *
 * <p><strong>Band is the only classification.</strong> {@link #band} returns the temperature type
 * ordinal of the accepted field, and every consumer - demand spread, achieved-ratio histogram,
 * supply accounting, admission - must go through it. There is deliberately no accessor for the raw
 * value a band was derived from: that value is only comparable against the threshold set of the
 * field that produced it, so a consumer holding it would have to re-implement the classification,
 * and any field with different thresholds would be classified twice, differently, in two places.
 */
public interface ClimateField {
    /** Band ordinal of the accepted field at a coordinate, under that field's own thresholds. */
    int band(int x, int z, MacroSample sample);
    /** Moisture ordinal, or -1 for temperature-only diagnostic fixtures. */
    default int humidityBand(int x,int z,MacroSample sample){return -1;}

    /** One dry sampled site of the field: the same list the field was built from, same order. */
    record Site(int x, int z, MacroSample sample) {}
}
