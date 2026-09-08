package io.github.luoyan.adventureworldgen.spatial;

/** Immutable horizontal world-space vector. */
public record Vec2(double x, double z) {
    public Vec2 {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("coordinates must be finite");
        }
    }

    public double distanceSquared(Vec2 other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return dx * dx + dz * dz;
    }

    public double distance(Vec2 other) {
        return StrictMath.sqrt(distanceSquared(other));
    }

    public Vec2 interpolate(Vec2 other, double t) {
        return new Vec2(x + (other.x - x) * t, z + (other.z - z) * t);
    }
}
