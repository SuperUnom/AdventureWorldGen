package io.github.luoyan.adventureworldgen.climate;

import io.github.luoyan.adventureworldgen.plan.ClimateSupply;

import java.util.List;

/**
 * Author demand statistics for a climate field under construction.
 *
 * <p>The field publishes these values with the plan, but it must not compute them: the computation
 * needs the author's expanded demand (which belongs to the planner) and the biome preference rules
 * (which belong to the biome layer). Injecting the computation keeps the field free of both, and it
 * is what lets the temperature and humidity fields live in their own package.
 *
 * <p>The three parts are separate calls on purpose. The field is still being initialised while they
 * run: demand ratios are computed from the unthresholded value before the accepted thresholds
 * exist, while the sampled share and the supply accounting read the accepted bands afterwards.
 * Computing them in one call would move the later two before those assignments and quietly change
 * the published statistics.
 *
 * <p>Diagnostic only: none of these values feeds back into the field, admission, scoring or any
 * search decision.
 */
public interface ClimateStatistics {
    /** Author demand spread across temperature bands and normalized to sum to one. */
    double[] targetRatios(ClimateField field, List<ClimateField.Site> sites);

    /** Share of the dry sampled sites that actually land in each band. */
    double[] actualRatios(ClimateField field, List<ClimateField.Site> sites);

    /** Per-demand legal and climate area accounting. */
    List<ClimateSupply> supply(ClimateField field, List<ClimateField.Site> sites);
}
