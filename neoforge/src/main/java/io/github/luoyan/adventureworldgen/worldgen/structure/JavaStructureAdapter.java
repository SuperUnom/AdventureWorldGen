package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

import java.util.List;
import java.util.Objects;

/** Generates and places an ordinary registered {@code Structure} at an explicit final position. */
public final class JavaStructureAdapter implements StructureAdapter {
    @Override
    public void generate(ServerLevel level, StructurePlacement placement) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(placement, "placement");

        var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(placement.structureId());
        if (structure == null) {
            throw new IllegalArgumentException("No registered Structure exists for "
                    + placement.structureId());
        }
        if (structure instanceof JigsawStructure) {
            throw new IllegalArgumentException("Registered structure is not an ordinary Structure: "
                    + placement.structureId());
        }
        if (placement.rotation() != Rotation.NONE) {
            throw new UnsupportedOperationException("Minecraft's Structure API does not support caller-supplied "
                    + "rotation for ordinary Structure entries: " + placement.structureId());
        }

        var chunkSource = level.getChunkSource();
        var chunkGenerator = chunkSource.getGenerator();
        StructureStart start;
        try {
            start = structure.generate(
                    level.registryAccess(),
                    chunkGenerator,
                    chunkGenerator.getBiomeSource(),
                    chunkSource.randomState(),
                    level.getStructureManager(),
                    level.getSeed(),
                    new ChunkPos(placement.position()),
                    0,
                    level,
                    biome -> true);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to create StructureStart for "
                    + placement.structureId(), exception);
        }

        if (start == null) {
            throw new IllegalStateException("Structure did not create a StructureStart: "
                    + placement.structureId());
        }
        if (!start.isValid() || start.getPieces().isEmpty()) {
            throw new IllegalStateException("StructureStart contains no placeable content: "
                    + placement.structureId());
        }

        BoundingBox generatedBounds = StructurePiece.createBoundingBox(start.getPieces().stream());
        int offsetX = placement.position().getX() - generatedBounds.minX();
        int offsetY = placement.position().getY() - generatedBounds.minY();
        int offsetZ = placement.position().getZ() - generatedBounds.minZ();
        start.getPieces().forEach(piece -> piece.move(offsetX, offsetY, offsetZ));

        BoundingBox bounds = start.getBoundingBox();
        List<ChunkPos> chunks = bounds.intersectingChunks().toList();
        for (ChunkPos chunk : chunks) {
            if (!level.isLoaded(chunk.getWorldPosition())) {
                throw new IllegalStateException("Cannot place StructureStart for " + placement.structureId()
                        + " because chunk " + chunk + " is not loaded");
            }
        }

        for (ChunkPos chunk : chunks) {
            BoundingBox writableArea = new BoundingBox(
                    chunk.getMinBlockX(),
                    level.getMinBuildHeight(),
                    chunk.getMinBlockZ(),
                    chunk.getMaxBlockX(),
                    level.getMaxBuildHeight(),
                    chunk.getMaxBlockZ());
            try {
                start.placeInChunk(
                        level,
                        level.structureManager(),
                        chunkGenerator,
                        level.getRandom(),
                        writableArea,
                        chunk);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Failed to place StructureStart for " + placement.structureId()
                        + " in chunk " + chunk, exception);
            }
        }
    }
}
