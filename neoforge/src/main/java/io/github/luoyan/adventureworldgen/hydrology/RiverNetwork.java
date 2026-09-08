package io.github.luoyan.adventureworldgen.hydrology;

import io.github.luoyan.adventureworldgen.spatial.Vec2;

import java.util.List;

/** Fully frozen river geometry and downstream-non-rising water profile. */
public record RiverNetwork(List<Channel> channels, List<Wetland> wetlands, String version) {
    public RiverNetwork { channels = List.copyOf(channels); wetlands = List.copyOf(wetlands); }

    public record Channel(String id, int order, String parentId, List<Vec2> points,
                          List<Double> cumulativeLengths, List<Double> waterSurfaces,
                          HydrologyProfile.RiverShape shape, LakeWidening lake) {
        public Channel {
            points = List.copyOf(points); cumulativeLengths = List.copyOf(cumulativeLengths);
            waterSurfaces = List.copyOf(waterSurfaces);
            if (points.size() < 2 || cumulativeLengths.size() != points.size()
                    || waterSurfaces.size() != points.size()) throw new IllegalArgumentException("unaligned river channel arrays");
            for (int i = 1; i < points.size(); i++) {
                if (!(cumulativeLengths.get(i) > cumulativeLengths.get(i - 1)))
                    throw new IllegalArgumentException("river points must advance downstream");
                if (waterSurfaces.get(i) > waterSurfaces.get(i - 1) + 1e-9)
                    throw new IllegalArgumentException("river water cannot rise downstream");
            }
        }
        public double length() { return cumulativeLengths.getLast(); }
    }

    public record LakeWidening(double along, double radius, double depth) {}
    public record Wetland(String id, Vec2 upstream, Vec2 downstream, double radius, double waterSurface) {}
}
