package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.worldgen.structure.ExecutionDataHolder;
import io.github.luoyan.adventureworldgen.worldgen.structure.Foundation;
import io.github.luoyan.adventureworldgen.worldgen.structure.TerrainSettings;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;

/** Chunk-local application of saved support areas and native density adjustments. Base queries stay structure-free. */
public final class StructureTerrain {
    static final int NATIVE_MARGIN = 12;
    private record Area(Foundation support, TerrainSettings settings) {}
    private final List<Area> areas;
    private final Beardifier nativeDensity;

    public StructureTerrain(StructureManager structures, ChunkPos chunk) {
        var result = new ArrayList<Area>();
        var volumes = new ObjectArrayList<Beardifier.Rigid>();
        var junctions = new ObjectArrayList<JigsawJunction>();
        // NONE structures may still have explicit, persisted supports.
        var starts = structures.startsForStructure(chunk, s -> true);
        for (var start : starts) {
            var data = ((ExecutionDataHolder) (Object) start).adventureworldgen$getExecutionData();
            if (data != null && data.terrain().mode() != TerrainSettings.Mode.NATIVE) {
                if (data.terrain().mode() != TerrainSettings.Mode.NONE)
                    for (var support : data.foundations()) result.add(new Area(support, data.terrain()));
                continue;
            }
            for (var piece : start.getPieces()) {
                if (!piece.isCloseToChunk(chunk, NATIVE_MARGIN)) continue;
                var adjustment = start.getStructure().terrainAdaptation();
                var box = piece.getBoundingBox();
                int delta = 0;
                boolean rigid = true;
                if (piece instanceof PieceBeardifierModifier modifier) {
                    adjustment = modifier.getTerrainAdjustment(); box = modifier.getBeardifierBox();
                    delta = modifier.getGroundLevelDelta();
                } else if (piece instanceof PoolElementStructurePiece pool) {
                    rigid = pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID;
                    delta = pool.getGroundLevelDelta();
                }
                if (data != null) {
                    // Planned native mode uses Mojang's density kernels, including bury/encapsulate.
                    if (rigid && adjustment != TerrainAdjustment.NONE)
                        volumes.add(new Beardifier.Rigid(box, adjustment, delta));
                    if (piece instanceof PoolElementStructurePiece pool && adjustment != TerrainAdjustment.NONE)
                        junctions.addAll(pool.getJunctions());
                } else if (rigid && (adjustment == TerrainAdjustment.BEARD_THIN || adjustment == TerrainAdjustment.BEARD_BOX)) {
                    // Preserve the existing terrain contract for unmanaged native structures.
                    result.add(new Area(new Foundation(box.minX(), box.minZ(), box.maxX(), box.maxZ(), box.minY() + delta),
                            new TerrainSettings(TerrainSettings.Mode.FLATTEN, NATIVE_MARGIN)));
                }
            }
        }
        result.sort(Comparator.comparingInt((Area a) -> a.support.surface()).thenComparingInt(a -> a.support.minX())
                .thenComparingInt(a -> a.support.minZ()).thenComparingInt(a -> a.support.maxX())
                .thenComparingInt(a -> a.support.maxZ()).thenComparing(a -> a.settings.mode())
                .thenComparingInt(a -> a.settings.margin()));
        volumes.sort(Comparator.comparingInt((Beardifier.Rigid r) -> r.box().minX()).thenComparingInt(r -> r.box().minZ())
                .thenComparingInt(r -> r.box().minY()).thenComparingInt(r -> r.box().maxX())
                .thenComparingInt(r -> r.box().maxZ()).thenComparingInt(r -> r.box().maxY())
                .thenComparing(r -> r.terrainAdjustment()).thenComparingInt(Beardifier.Rigid::groundLevelDelta));
        junctions.sort(Comparator.comparingInt(JigsawJunction::getSourceX).thenComparingInt(JigsawJunction::getSourceZ)
                .thenComparingInt(JigsawJunction::getSourceGroundY));
        areas = List.copyOf(result);
        nativeDensity = volumes.isEmpty() && junctions.isEmpty() ? null : new Beardifier(volumes.iterator(), junctions.iterator());
    }

    public boolean isEmpty() { return areas.isEmpty() && nativeDensity == null; }

    public int surfaceAt(int x, int z, int original) {
        double strongest = 0, weighted = 0, total = 0;
        for (var area : areas) {
            double weight = area.support.weight(x, z, area.settings.margin());
            if (weight == 0) continue;
            int target = area.settings.mode() == TerrainSettings.Mode.FILL ? Math.max(original, area.support.surface()) : area.support.surface();
            if (weight == 1) return target;
            strongest = Math.max(strongest, weight);
            weighted += target * weight; total += weight;
        }
        return total == 0 ? original : (int) Math.round(original + strongest * (weighted / total - original));
    }

    public void applyNativeDensity(NoiseColumn column, int x, int z, int floor, int minY, int maxY) {
        if (nativeDensity == null) return;
        for (int y = minY + 1; y < maxY; y++) {
            double contribution = nativeDensity.compute(new DensityFunction.SinglePointContext(x, y, z));
            if (contribution == 0) continue;
            var state = column.getBlock(y);
            double base = Math.max(-1, Math.min(1, (floor - y - .5) / 12.0));
            base = state.blocksMotion() ? Math.max(.05, base) : Math.min(-.05, base);
            if (base + contribution > 0) column.setBlock(y, Blocks.STONE.defaultBlockState());
            else if (state.blocksMotion()) column.setBlock(y, Blocks.AIR.defaultBlockState());
        }
    }
}
