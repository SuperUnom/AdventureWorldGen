package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

import java.util.Objects;

/** Validates the ordinary Structure entry while StructureStart placement policy is pending. */
public final class JavaStructureAdapter implements StructureAdapter {
    @Override
    public void generate(ServerLevel level, StructurePlacement placement) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(placement, "placement");

        var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(placement.structureId());
        if (structure == null || structure instanceof JigsawStructure) {
            throw new IllegalArgumentException("Registered structure is not an ordinary Structure: "
                    + placement.structureId());
        }
        throw new UnsupportedOperationException("Exact-position StructureStart generation is not implemented: "
                + placement.structureId());
    }
}
