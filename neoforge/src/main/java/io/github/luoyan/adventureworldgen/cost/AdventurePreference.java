package io.github.luoyan.adventureworldgen.cost;

/** Smooth preference in normalized adventure-level units, with no forbidden level band. */
public final class AdventurePreference {
    private AdventurePreference() {}
    public static double penalty(int requested, double actual) {
        double delta=actual-requested;
        double outside=StrictMath.max(0,StrictMath.abs(delta)-0.35);
        // Late content appearing too early costs more than a modest late arrival.
        return 0.05*delta*delta + outside*outside*(delta<0?1.6:1.0);
    }
}
