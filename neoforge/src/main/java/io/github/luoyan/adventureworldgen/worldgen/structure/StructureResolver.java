package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

import java.util.Objects;

/** Resolves structure content by runtime type without relying on identifier naming conventions. */
public class StructureResolver {
    public StructureType resolve(ServerLevel level, StructurePlacement placement) {
        Objects.requireNonNull(placement, "placement");
        return resolve(level, placement.structureId());
    }

    public StructureType resolve(ServerLevel level, ResourceLocation structureId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(structureId, "structureId");

        if (level.getStructureManager().get(structureId).isPresent()) {
            return StructureType.TEMPLATE;
        }

        var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(structureId);
        if (structure instanceof JigsawStructure) {
            return StructureType.JIGSAW;
        }
        if (structure != null) {
            return StructureType.JAVA_STRUCTURE;
        }
        return StructureType.UNKNOWN;
    }
}
