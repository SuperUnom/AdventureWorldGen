package io.github.luoyan.adventureworldgen.plan;

import io.github.luoyan.adventureworldgen.spatial.CellMask;

/**
 * One planned biome patch: a concrete occurrence of a biome with its own ownership,
 * area and adventure level.
 *
 * <p>This is shared plan data, deliberately independent of the runtime plan object so
 * planning code can produce it without depending on runtime assembly or Minecraft.
 * Field names are part of the persisted plan-v3 wire format; renaming a component or
 * changing its order requires a reviewed format change.
 */
public record PlannedBiomePatch(String patchId, ContentId biomeId, int adventureLevel,
                                int minX, int minZ, int maxXExclusive, int maxZExclusive,
                                CellMask mask, int anchorX, int anchorZ) {
    public PlannedBiomePatch(String id, ContentId biome, int level, int minX, int minZ, int maxX, int maxZ) {
        this(id, biome, level, minX, minZ, maxX, maxZ, null, (minX + maxX) / 2, (minZ + maxZ) / 2);
    }

    public PlannedBiomePatch {
        if (minX >= maxXExclusive || minZ >= maxZExclusive) throw new IllegalArgumentException("empty biome patch");
        if (mask != null && !mask.contains(anchorX, anchorZ)) throw new IllegalArgumentException("anchor outside ownership mask");
        if (mask != null) for (long cell : mask.cells()) {
            int x = CellMask.x(cell);
            int z = CellMask.z(cell);
            if (x < minX || z < minZ || x >= maxXExclusive || z >= maxZExclusive)
                throw new IllegalArgumentException("ownership outside patch bounds");
        }
    }

    public boolean contains(int x, int z) {
        if (x < minX || z < minZ || x >= maxXExclusive || z >= maxZExclusive) return false;
        if (mask != null) return mask.contains(x, z);
        x = Math.floorDiv(x, 4) * 4 + 2;
        z = Math.floorDiv(z, 4) * 4 + 2;
        double hx = (maxXExclusive - minX) * 0.5, hz = (maxZExclusive - minZ) * 0.5;
        double dx = (x + 0.5 - (minX + hx)) / hx, dz = (z + 0.5 - (minZ + hz)) / hz;
        double angle = StrictMath.atan2(dz, dx);
        double phase = (patchId.hashCode() & 0xffff) * (StrictMath.PI * 2.0 / 65536.0);
        // An irregular closed contour, bounded by the persisted box. The planner counts
        // its actual 4-block cells and validates structure protection against this same mask.
        double radius = 0.74 + 0.06 * StrictMath.sin(3 * angle + phase)
                + 0.035 * StrictMath.sin(7 * angle - phase) + 0.025 * StrictMath.sin(13 * angle + phase);
        return StrictMath.hypot(dx, dz) < radius;
    }

    public long area() {
        if (mask != null) return mask.size() * 16L;
        long cells = 0;
        for (int x = minX; x < maxXExclusive; x += 4)
            for (int z = minZ; z < maxZExclusive; z += 4)
                if (contains(x + 2, z + 2)) cells++;
        return cells * 16;
    }
}
