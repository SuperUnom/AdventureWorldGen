package io.github.luoyan.adventureworldgen.plan;

/** Inclusive horizontal block bounds, shared by planning and execution validation. */
public record BoundsXZ(int minX, int minZ, int maxX, int maxZ) {
    public BoundsXZ {
        if (minX > maxX || minZ > maxZ || Math.abs((long) minX) > 30_000_000
                || Math.abs((long) maxX) > 30_000_000 || Math.abs((long) minZ) > 30_000_000
                || Math.abs((long) maxZ) > 30_000_000) throw new IllegalArgumentException("invalid horizontal bounds");
    }
    public BoundsXZ translate(int x, int z) {
        return new BoundsXZ(Math.addExact(minX,x), Math.addExact(minZ,z), Math.addExact(maxX,x), Math.addExact(maxZ,z));
    }
    public BoundsXZ expand(int margin) {
        if (margin < 0) throw new IllegalArgumentException("negative bounds margin");
        return new BoundsXZ(Math.subtractExact(minX,margin),Math.subtractExact(minZ,margin),
                Math.addExact(maxX,margin),Math.addExact(maxZ,margin));
    }
    public boolean contains(double x, double z, double margin) {
        // Coordinates are block centres; the occupied block rectangle has half-open outer edges.
        return x >= minX-margin && x <= maxX+1.0+margin && z >= minZ-margin && z <= maxZ+1.0+margin;
    }
    public boolean contains(BoundsXZ other) {
        return other.minX>=minX && other.maxX<=maxX && other.minZ>=minZ && other.maxZ<=maxZ;
    }
    public BoundsXZ union(BoundsXZ other) {
        return new BoundsXZ(Math.min(minX,other.minX),Math.min(minZ,other.minZ),Math.max(maxX,other.maxX),Math.max(maxZ,other.maxZ));
    }
}
