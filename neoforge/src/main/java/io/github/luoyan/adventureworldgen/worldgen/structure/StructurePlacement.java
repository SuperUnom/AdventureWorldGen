package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;

import java.util.Objects;

/** A caller-supplied, final position for explicit structure execution. */
public record StructurePlacement(ResourceLocation structureId, BlockPos position, Rotation rotation) {
    public StructurePlacement {
        Objects.requireNonNull(structureId, "structureId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotation, "rotation");
        position = position.immutable();
    }
}
