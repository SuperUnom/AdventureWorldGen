package io.github.luoyan.adventureworldgen.worldgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;

/** Heightfield equivalent of surface-structure adaptation, with solid foundations to the terrain.
 * Base-height queries remain structure-free, as required when jigsaw starts are being assembled. */
final class StructureTerrain {
    private static final int MARGIN = 12;
    private final List<Foundation> foundations;
    private record Foundation(BoundingBox box, int surface) {}

    StructureTerrain(StructureManager structures, ChunkPos chunk) {
        var result = new ArrayList<Foundation>();
        for (var start : structures.startsForStructure(chunk, s -> s.terrainAdaptation() != TerrainAdjustment.NONE)) {
            for (var piece : start.getPieces()) {
                if (!piece.isCloseToChunk(chunk, MARGIN)) continue;
                var adjustment = start.getStructure().terrainAdaptation();
                var box = piece.getBoundingBox();
                int delta = 0;
                if (piece instanceof PieceBeardifierModifier modifier) {
                    adjustment = modifier.getTerrainAdjustment();
                    box = modifier.getBeardifierBox();
                    delta = modifier.getGroundLevelDelta();
                } else if (piece instanceof PoolElementStructurePiece pool) {
                    if (pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) continue;
                    delta = pool.getGroundLevelDelta();
                }
                // BURY/ENCAPSULATE belong to underground structures, not surface terraces.
                if (adjustment == TerrainAdjustment.BEARD_THIN || adjustment == TerrainAdjustment.BEARD_BOX)
                    result.add(new Foundation(box, box.minY() + delta));
            }
        }
        // References may arrive in a different order in adjacent chunks.
        result.sort(Comparator.comparingInt(Foundation::surface).thenComparingInt(f -> f.box.minX())
                .thenComparingInt(f -> f.box.minZ()));
        foundations = List.copyOf(result);
    }

    boolean isEmpty() { return foundations.isEmpty(); }

    int surfaceAt(int x, int z, int original) {
        double strongest = 0, weighted = 0, total = 0;
        for (var foundation : foundations) {
            var box = foundation.box;
            double dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX()));
            double dz = Math.max(0, Math.max(box.minZ() - z, z - box.maxZ()));
            double t = Math.max(0, 1 - Math.hypot(dx, dz) / MARGIN);
            if (t == 0) continue;
            double weight = t * t * (3 - 2 * t);
            // Inside a rigid footprint its exact ground datum wins over neighbouring ramps.
            if (t == 1) return foundation.surface;
            strongest = Math.max(strongest, weight);
            weighted += foundation.surface * weight;
            total += weight;
        }
        return total == 0 ? original : (int)Math.round(original + strongest * (weighted / total - original));
    }
}
