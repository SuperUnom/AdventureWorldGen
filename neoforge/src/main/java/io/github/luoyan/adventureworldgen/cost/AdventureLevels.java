package io.github.luoyan.adventureworldgen.cost;

import io.github.luoyan.adventureworldgen.planner.PlanningFailure;
import io.github.luoyan.adventureworldgen.spatial.Vec2;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Coast-median normalization shared by biome seeds and structure entrances. */
public final class AdventureLevels {
    private final double coastReference;
    private final double tolerance;

    private AdventureLevels(double coastReference, double tolerance) {
        this.coastReference = coastReference;
        this.tolerance = tolerance;
    }

    public static AdventureLevels fromCoast(CostDistanceMap costs, List<Vec2> coastSamples, double tolerance) {
        long[] reachable = coastSamples.stream().mapToLong(costs::costAt)
                .filter(cost -> cost != CostDistanceMap.UNREACHABLE).sorted().toArray();
        if (reachable.length == 0) throw invalidReference("no coastline sample is reachable", 0);
        double median = reachable.length % 2 == 1 ? reachable[reachable.length / 2]
                : reachable[reachable.length / 2 - 1] / 2.0 + reachable[reachable.length / 2] / 2.0;
        if (!(median > 0.0) || !Double.isFinite(median)) throw invalidReference("coast reference is not positive", reachable.length);
        return new AdventureLevels(median, tolerance);
    }

    public Interval interval(int level) {
        if (level < 0 || level > 10) throw new IllegalArgumentException("level must be in [0,10]");
        double target = coastReference * level / 10.0;
        double halfWidth = tolerance * coastReference / 10.0;
        return new Interval(StrictMath.max(0.0, target - halfWidth), target + halfWidth);
    }

    public boolean contains(int level, long microCost) {
        if (microCost == CostDistanceMap.UNREACHABLE) return false;
        Interval interval = interval(level);
        return microCost >= interval.minimum && microCost <= interval.maximum;
    }

    public double coastReference() { return coastReference; }

    private static PlanningFailure invalidReference(String reason, int reachable) {
        return new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, "adventure-levels", reason,
                Map.of("reachable_coast_samples", reachable));
    }

    public record Interval(double minimum, double maximum) {}
}
