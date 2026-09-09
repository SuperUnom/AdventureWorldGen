package io.github.luoyan.adventureworldgen.worldgen;

import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;

/** Do not seed exposed waterfalls on cold high slopes; underground springs still generate. */
public final class MountainSpringFilter {
    private MountainSpringFilter() {}

    public static boolean suppress(FeaturePlaceContext<SpringConfiguration> context) {
        if (!(context.chunkGenerator() instanceof AdventureChunkGenerator)
                || !context.config().state.is(FluidTags.WATER)) return false;
        var pos = context.origin();
        var level = context.level();
        if (pos.getY() < context.chunkGenerator().getSeaLevel() + 32
                || !level.getBiome(pos).value().coldEnoughToSnow(pos)) return false;
        for (var direction : Direction.Plane.HORIZONTAL) {
            var opening = pos.relative(direction);
            if (level.isEmptyBlock(opening) && level.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                    opening.getX(), opening.getZ()) <= pos.getY() + 8) return true;
        }
        return false;
    }
}
