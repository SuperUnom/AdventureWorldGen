package io.github.luoyan.adventureworldgen.cost;

import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.WaterKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Direct integration of the planner-v2 directed slope and water cost formula. */
public final class EdgeCostCalculator {
    public static final long MICROS_PER_COST = 1_000_000L;
    private final MacroTerrain terrain;
    private final BoundaryIntersector boundaries;
    private final double maximumSpacing;

    public EdgeCostCalculator(MacroTerrain terrain, BoundaryIntersector boundaries, double maximumSpacing) {
        if (!(maximumSpacing > 0.0)) throw new IllegalArgumentException("maximumSpacing must be positive");
        this.terrain = terrain;
        this.boundaries = boundaries;
        this.maximumSpacing = maximumSpacing;
    }

    public EdgeCost calculate(double x0, double z0, double x1, double z1) {
        double length = StrictMath.hypot(x1 - x0, z1 - z0);
        if (!(length > 0.0) || !Double.isFinite(length)) throw new IllegalArgumentException("edge must have finite non-zero length");
        int divisions = (int) StrictMath.ceil(length / maximumSpacing);
        ArrayList<Double> parameters = new ArrayList<>(divisions + 1);
        for (int i = 0; i <= divisions; i++) parameters.add(i / (double) divisions);
        for (double intersection : boundaries.intersections(x0, z0, x1, z1)) {
            if (Double.isFinite(intersection) && intersection > 0.0 && intersection < 1.0) parameters.add(intersection);
        }
        parameters.sort(Comparator.naturalOrder());
        List<Double> unique = new ArrayList<>(parameters.size());
        for (double value : parameters) {
            if (unique.isEmpty() || value - unique.getLast() > 1.0e-12) unique.add(value);
        }

        MacroSample previous = terrain.sample(x0, z0);
        if (blocked(previous)) return EdgeCost.BLOCKED;
        double forward = 0.0, reverse = 0.0;
        double previousT = 0.0;
        for (int i = 1; i < unique.size(); i++) {
            double t = unique.get(i);
            MacroSample next = terrain.sample(lerp(x0, x1, t), lerp(z0, z1, t));
            double middleT = (previousT + t) * 0.5;
            MacroSample middle = terrain.sample(lerp(x0, x1, middleT), lerp(z0, z1, middleT));
            if (blocked(next) || blocked(middle)) return EdgeCost.BLOCKED;
            double segmentLength = length * (t - previousT);
            double slope = (next.travelSurface() - previous.travelSurface()) / segmentLength;
            if (!Double.isFinite(slope) || StrictMath.abs(slope) > 2.0) return EdgeCost.BLOCKED;
            double water = StrictMath.max(multiplier(previous), StrictMath.max(multiplier(middle), multiplier(next)));
            forward += segmentLength * slopeFactor(slope) * water;
            reverse += segmentLength * slopeFactor(-slope) * water;
            previous = next;
            previousT = t;
        }
        return new EdgeCost(EdgeCost.State.READY, quantize(forward), quantize(reverse));
    }

    private static boolean blocked(MacroSample sample) {
        return sample.hazardous() || sample.waterKind() == WaterKind.OCEAN || sample.waterKind() == WaterKind.LAVA;
    }

    private static double multiplier(MacroSample sample) {
        if (!sample.wet()) return 1.0;
        return sample.waterDepth() <= 1.5 ? 2.0 : 5.0;
    }

    private static double slopeFactor(double slope) {
        return 1.0 + 4.0 * StrictMath.max(slope, 0.0) + 2.0 * StrictMath.max(-slope, 0.0) + 6.0 * slope * slope;
    }

    private static long quantize(double value) {
        double micros = StrictMath.rint(value * MICROS_PER_COST);
        if (!Double.isFinite(micros) || micros > Long.MAX_VALUE) throw new ArithmeticException("edge cost overflow");
        return StrictMath.max(1L, (long) micros);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
