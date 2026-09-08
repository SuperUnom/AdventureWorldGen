package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.StructureAdapter;
import io.github.luoyan.adventureworldgen.config.ContentId;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.core.Direction;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Freezes the sole supported v1 vanilla structure into its canonical piece NBT. */
public final class VanillaDesertPyramidAdapter implements StructureAdapter {
    public static final ContentId ID = new ContentId("minecraft:desert_pyramid");

    @Override public ContentId structureId() { return ID; }
    @Override public String adapterVersion() { return "vanilla-desert-pyramid-v1"; }
    @Override public Descriptor describe() {
        return new Descriptor(List.of("north", "east", "south", "west"), 15.0, true, true);
    }

    @Override
    public Prepared prepare(Candidate candidate, long structureSeed) {
        DesertPyramidPiece piece = new DesertPyramidPiece(RandomSource.create(structureSeed),
                candidate.originX(), candidate.originZ());
        piece.setOrientation(switch (candidate.rotation()) {
            case "north" -> Direction.NORTH;
            case "east" -> Direction.EAST;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            default -> throw new IllegalArgumentException("unsupported desert pyramid rotation " + candidate.rotation());
        });
        piece.move(0, candidate.originY() - piece.getBoundingBox().minY(), 0);
        BoundingBox box = piece.getBoundingBox();
        byte[] nbt;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            // This piece's save implementation is registry-context independent.
            var tag = piece.createTag(null);
            tag.putInt("HPos", candidate.originY());
            NbtIo.write(tag, output);
            output.flush();
            nbt = bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("could not freeze desert pyramid piece", failure);
        }
        var planned = new AdventurePlanView.PlannedPiece(candidate.instanceId() + "/piece/0",
                box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), nbt);
        var footprint = new HorizontalBox(box.minX(), box.minZ(), box.maxX(), box.maxZ());
        var protection = new HorizontalBox(box.minX() - 8, box.minZ() - 8, box.maxX() + 8, box.maxZ() + 8);
        int entranceX = candidate.originX(), entranceZ = candidate.originZ();
        switch (candidate.rotation()) {
            case "north" -> entranceZ -= 11;
            case "east" -> entranceX += 11;
            case "south" -> entranceZ += 11;
            case "west" -> entranceX -= 11;
            default -> throw new IllegalArgumentException("unsupported desert pyramid rotation " + candidate.rotation());
        }
        return new Prepared(candidate, List.of(planned), List.of(footprint), List.of(protection),
                entranceX, candidate.originY() + 1, entranceZ);
    }

    @Override
    public List<String> validatePrepared(Prepared structure, MacroTerrain terrain) {
        List<String> errors = new ArrayList<>();
        for (HorizontalBox box : structure.footprint()) {
            double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
            int[][] points = {{box.minX(), box.minZ()}, {box.minX(), box.maxZ()},
                    {box.maxX(), box.minZ()}, {box.maxX(), box.maxZ()}};
            for (int[] point : points) {
                var sample = terrain.sample(point[0] + 0.5, point[1] + 0.5);
                if (sample.wet() || sample.hazardous()) errors.add("footprint is wet or hazardous");
                minimum = StrictMath.min(minimum, sample.groundSurface());
                maximum = StrictMath.max(maximum, sample.groundSurface());
            }
            if (maximum - minimum > 8.0) errors.add("footprint support delta exceeds 8 blocks");
        }
        return List.copyOf(errors);
    }

    @Override public byte[] serializePieces(Prepared structure) {
        if (structure.pieces().size() != 1) throw new IllegalArgumentException("desert pyramid must have one piece");
        return structure.pieces().getFirst().canonicalNbt();
    }

    @Override public void placeChunk(Prepared structure, int chunkX, int chunkZ, PlacementTarget target) {
        for (var piece : structure.pieces()) if (target.beginOnce(structure.candidate().instanceId(), piece.pieceId(), chunkX, chunkZ))
            target.placeCanonicalPiece(piece);
    }
}
