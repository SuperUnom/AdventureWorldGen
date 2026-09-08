package io.github.luoyan.adventureworldgen.terrain;

/** Two neighbouring labels share a bounded fringe; all other labels retain no weight.
 * Costs and band are in the same units. Label order fixes the noise orientation on both
 * sides of a boundary, so crossing the nearest-site bisector does not flip the noise. */
public final class EcotoneSelector {
    private EcotoneSelector() {}

    public static int select(double[] costs, double threshold, double band) {
        if (!(band > 0)) throw new IllegalArgumentException("positive ecotone band required");
        int first = -1, second = -1;
        for (int i = 0; i < costs.length; i++) {
            if (!Double.isFinite(costs[i])) continue;
            if (first < 0 || costs[i] < costs[first]) { second = first; first = i; }
            else if (second < 0 || costs[i] < costs[second]) second = i;
        }
        if (second < 0 || costs[second] - costs[first] >= band) return first;
        int a = Math.min(first, second), b = Math.max(first, second);
        double probabilityA = 0.5 + (costs[b] - costs[a]) / (2 * band);
        return threshold < probabilityA ? a : b;
    }
}
