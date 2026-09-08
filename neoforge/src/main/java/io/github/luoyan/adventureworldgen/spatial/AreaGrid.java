package io.github.luoyan.adventureworldgen.spatial;

import io.github.luoyan.adventureworldgen.planner.PlannerProfile;

/** Coordinate conversion for the final world-aligned 4x4 biome ownership grid. */
public final class AreaGrid {
    public static final int CELL_SIDE = PlannerProfile.V2.finalAreaCellSide();
    public static final int CELL_AREA = CELL_SIDE * CELL_SIDE;

    private AreaGrid() {
    }

    public static Cell cellAtBlock(int blockX, int blockZ) {
        return new Cell(Math.floorDiv(blockX, CELL_SIDE), Math.floorDiv(blockZ, CELL_SIDE));
    }

    public static Cell cellAtPosition(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("coordinates must be finite");
        }
        double cellX = StrictMath.floor(x / CELL_SIDE);
        double cellZ = StrictMath.floor(z / CELL_SIDE);
        if (cellX < Integer.MIN_VALUE || cellX > Integer.MAX_VALUE
                || cellZ < Integer.MIN_VALUE || cellZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("coordinates exceed the supported grid range");
        }
        return new Cell((int) cellX, (int) cellZ);
    }

    public record Cell(int x, int z) {
        public int minBlockX() {
            return Math.multiplyExact(x, CELL_SIDE);
        }

        public int minBlockZ() {
            return Math.multiplyExact(z, CELL_SIDE);
        }

        public double sampleX() {
            return (double) x * CELL_SIDE + CELL_SIDE / 2.0;
        }

        public double sampleZ() {
            return (double) z * CELL_SIDE + CELL_SIDE / 2.0;
        }

        public boolean contains(int blockX, int blockZ) {
            return AreaGrid.cellAtBlock(blockX, blockZ).equals(this);
        }
    }
}
