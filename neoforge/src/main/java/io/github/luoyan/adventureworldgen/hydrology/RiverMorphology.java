package io.github.luoyan.adventureworldgen.hydrology;

import io.github.luoyan.adventureworldgen.terrain.GradientNoise;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Continuous reach widths and independently irregular banks, derived from frozen geometry. */
public final class RiverMorphology {
    public static final String VERSION = "ftf-hydrology-adapted-v2";
    private final GradientNoise reaches, bars, banks, detail;
    private final Map<String, List<Inflow>> inflows;

    public RiverMorphology(RiverNetwork network) {
        long seed = network.hashCode();
        reaches = new GradientNoise(seed, "river/reaches", 140);
        bars = new GradientNoise(seed, "river/bars", 43);
        banks = new GradientNoise(seed, "river/banks", 31);
        detail = new GradientNoise(seed, "river/bank-detail", 9);
        Map<String, Double> drainage = new HashMap<>();
        Map<String, RiverNetwork.Channel> channels = new HashMap<>();
        for (var channel : network.channels()) {
            drainage.put(channel.id(), channel.length()); channels.put(channel.id(), channel);
        }
        // Accumulate the tributary tree before building downstream discharge profiles.
        var upstreamFirst = network.channels().stream().sorted(java.util.Comparator.comparingInt(RiverNetwork.Channel::order).reversed()).toList();
        for (var channel : upstreamFirst) if (channels.containsKey(channel.parentId()))
            drainage.merge(channel.parentId(), drainage.get(channel.id()), Double::sum);
        Map<String, List<Inflow>> incoming = new HashMap<>();
        for (var channel : network.channels()) {
            var parent = channels.get(channel.parentId());
            if (parent == null) continue;
            double at = alongAt(parent, channel.points().getLast());
            incoming.computeIfAbsent(parent.id(), ignored -> new ArrayList<>())
                    .add(new Inflow(at, drainage.get(channel.id())));
        }
        Map<String, List<Inflow>> frozen = new HashMap<>();
        incoming.forEach((id, flows) -> frozen.put(id, List.copyOf(flows)));
        inflows = Map.copyOf(frozen);
    }

    public double bedRadius(RiverNetwork.Channel channel, double along, double centerX, double centerZ,
                            double x, double z) {
        double t = StrictMath.max(0, StrictMath.min(1, along));
        double discharge = 0.10 + t;
        double transition = StrictMath.min(0.15, 60 / channel.length());
        for (var inflow : inflows.getOrDefault(channel.id(), List.of())) {
            double join = StrictMath.max(0, StrictMath.min(1, (t - inflow.along + transition) / (2 * transition)));
            discharge += inflow.drainage / channel.length() * join * join * (3 - 2 * join);
        }
        double width = channel.shape().bedWidth() * StrictMath.pow(discharge, 0.42)
                * (1 + 0.62 * reaches.sample(centerX, centerZ) + 0.28 * bars.sample(centerX, centerZ));
        // World-space sampling gives the two banks different coves without moving the
        // centerline or introducing a discontinuity at segment or chunk boundaries.
        width += StrictMath.min(6, width * 0.24) * banks.sample(x, z)
                + StrictMath.min(1.8, width * 0.09) * detail.sample(x, z);
        // A spring-fed headwater emerges as a narrow rill and gains its ordinary width
        // over a variable 72..152-block reach instead of starting with a blunt full-width cap.
        double sourceFactor=headwaterFactor(channel,t);
        return StrictMath.max(1.15, StrictMath.min(maximumBedRadius(channel.shape()), width*sourceFactor));
    }

    public static double maximumBedRadius(HydrologyProfile.RiverShape shape) {
        return shape.bedWidth() * 2.4;
    }

    public double bedDepth(RiverNetwork.Channel channel,double along,double centerX,double centerZ,double radius) {
        double ordinary=channel.shape().bedDepth()*(0.72+0.28*StrictMath.sqrt(radius/channel.shape().bedWidth())
                +0.24*bars.sample(centerZ,centerX));
        double source=StrictMath.min(1,along*channel.length()/96.0);
        return StrictMath.max(1.1,ordinary*(.35+.65*smooth(source)));
    }

    double headwaterFactor(RiverNetwork.Channel channel,double along) {
        double sourceReach=StrictMath.min(channel.length()*.18,72+80*(.5+.5*reaches.sample(
                channel.points().getFirst().x(),channel.points().getFirst().z())));
        return .28+.72*smooth(StrictMath.min(1,along*channel.length()/StrictMath.max(1,sourceReach)));
    }

    private static double smooth(double value) { return value*value*(3-2*value); }

    private static double alongAt(RiverNetwork.Channel channel, Vec2 point) {
        double best = Double.POSITIVE_INFINITY, along = 0;
        for (int i = 1; i < channel.points().size(); i++) {
            Vec2 a = channel.points().get(i - 1), b = channel.points().get(i);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double t = StrictMath.max(0, StrictMath.min(1, ((point.x() - a.x()) * dx + (point.z() - a.z()) * dz) / a.distanceSquared(b)));
            double distance = point.distanceSquared(a.interpolate(b, t));
            if (distance < best) {
                best = distance;
                along = (channel.cumulativeLengths().get(i - 1) + t * a.distance(b)) / channel.length();
            }
        }
        return along;
    }
    private record Inflow(double along, double drainage) {}
}
