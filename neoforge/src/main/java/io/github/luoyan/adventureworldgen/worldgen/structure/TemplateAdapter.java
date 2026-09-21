package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

import java.util.Objects;

/** Places NBT templates loaded by Minecraft's {@code StructureTemplateManager}. */
public final class TemplateAdapter implements StructureAdapter {
    @Override
    public void generate(net.minecraft.server.level.ServerLevel level, StructurePlacement placement) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(placement, "placement");

        var template = level.getStructureManager().get(placement.structureId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No structure template exists for " + placement.structureId()));
        var settings = new StructurePlaceSettings().setRotation(placement.rotation());
        long seed = level.getSeed()
                ^ placement.position().asLong()
                ^ placement.structureId().toString().hashCode();
        boolean placed = template.placeInWorld(
                level,
                placement.position(),
                placement.position(),
                settings,
                RandomSource.create(seed),
                Block.UPDATE_CLIENTS);
        if (!placed) {
            throw new IllegalStateException("Structure template contains no placeable content: "
                    + placement.structureId());
        }
    }
}
