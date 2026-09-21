package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;
import java.util.ArrayList;
import java.util.List;

public final class JigsawTerrain {
    private JigsawTerrain() {}
    public static List<Foundation> supports(StructureStart start) {
        var supports = new ArrayList<Foundation>();
        for (var piece : start.getPieces()) {
            if (piece instanceof PoolElementStructurePiece pool
                    && pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
                var box = piece.getBoundingBox();
                int ground = pool.getGroundLevelDelta();
                if (piece instanceof PieceBeardifierModifier modifier) {
                    box = modifier.getBeardifierBox(); ground = modifier.getGroundLevelDelta();
                }
                supports.add(new Foundation(box.minX(), box.minZ(), box.maxX(), box.maxZ(), box.minY() + ground));
            }
        }
        return List.copyOf(supports);
    }
}
