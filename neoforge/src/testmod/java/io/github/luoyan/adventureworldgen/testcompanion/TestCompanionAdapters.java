package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.BiomeAdapter;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.StructureAdapter;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.config.ContentId;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** External adapters used only by the isolated test companion source set. */
public final class TestCompanionAdapters {
    public static final ContentId ASHEN_GROVE_ID = new ContentId("testcompanion:ashen_grove");
    public static final ContentId WAYSTATION_ID = new ContentId("testcompanion:waystation");

    public static final BiomeAdapter ASHEN_GROVE = new BiomeAdapter() {
        private final SurfacePalette surface = new SurfacePalette(new ContentId("minecraft:podzol"),
                new ContentId("minecraft:coarse_dirt"), new ContentId("minecraft:stone"), 4);
        @Override public ContentId biomeId() { return ASHEN_GROVE_ID; }
        @Override public String adapterVersion() { return "testcompanion-ashen-grove-v1"; }
        @Override public Compatibility compatibility(MacroSample terrain) {
            boolean allowed = terrain.waterKind() == WaterKind.NONE && !terrain.hazardous();
            return new Compatibility(allowed, allowed ? 0.75 : 0.0, allowed ? "dry custom biome" : "requires dry terrain");
        }
        @Override public SurfacePalette surface(MacroSample terrain) { return surface; }
    };

    public static final StructureAdapter WAYSTATION = new StructureAdapter() {
        @Override public ContentId structureId() { return WAYSTATION_ID; }
        @Override public String adapterVersion() { return "testcompanion-waystation-v1"; }
        @Override public Descriptor describe() {
            return new Descriptor(List.of("north", "east", "south", "west"), 32.0, true, true);
        }
        @Override public Prepared prepare(Candidate candidate, long structureSeed) {
            List<AdventurePlanView.PlannedPiece> pieces = new ArrayList<>();
            pieces.add(piece(candidate, 0, candidate.originX() - 12, candidate.originZ() - 6,
                    candidate.originX() + 12, candidate.originZ() + 6, structureSeed));
            pieces.add(piece(candidate, 1, candidate.originX() + 5, candidate.originZ() - 4,
                    candidate.originX() + 25, candidate.originZ() + 4, structureSeed));
            List<HorizontalBox> footprint = pieces.stream().map(piece -> new HorizontalBox(
                    piece.minX(), piece.minZ(), piece.maxX(), piece.maxZ())).toList();
            return new Prepared(candidate, pieces, footprint,
                    List.of(new HorizontalBox(candidate.originX() - 16, candidate.originZ() - 10,
                            candidate.originX() + 29, candidate.originZ() + 16)),
                    candidate.originX(), candidate.originY() + 1, candidate.originZ() - 7);
        }
        @Override public List<String> validatePrepared(Prepared structure, MacroTerrain terrain) {
            List<String> errors = new ArrayList<>();
            for (HorizontalBox box : structure.footprint()) {
                for (int x : new int[]{box.minX(), box.maxX()}) for (int z : new int[]{box.minZ(), box.maxZ()}) {
                    var sample = terrain.sample(x + 0.5, z + 0.5);
                    if (sample.wet() || sample.hazardous()) errors.add("waystation footprint requires safe dry terrain");
                }
            }
            return List.copyOf(errors);
        }
        @Override public byte[] serializePieces(Prepared structure) {
            int length = structure.pieces().stream().mapToInt(piece -> piece.canonicalNbt().length).sum();
            byte[] result = new byte[length]; int offset = 0;
            for (var piece : structure.pieces()) {
                System.arraycopy(piece.canonicalNbt(), 0, result, offset, piece.canonicalNbt().length);
                offset += piece.canonicalNbt().length;
            }
            return result;
        }
        @Override public void placeChunk(Prepared structure, int chunkX, int chunkZ, PlacementTarget target) {
            int minX = chunkX << 4, minZ = chunkZ << 4, maxX = minX + 15, maxZ = minZ + 15;
            for (var piece : structure.pieces()) {
                if (piece.minX() > maxX || piece.maxX() < minX || piece.minZ() > maxZ || piece.maxZ() < minZ) continue;
                if (target.beginOnce(structure.candidate().instanceId(), piece.pieceId(), chunkX, chunkZ))
                    target.placeCanonicalPiece(piece);
            }
        }
    };

    private TestCompanionAdapters() {}

    private static AdventurePlanView.PlannedPiece piece(StructureAdapter.Candidate candidate, int index,
                                                         int minX, int minZ, int maxX, int maxZ, long seed) {
        DesertPyramidPiece vanillaPiece = new DesertPyramidPiece(RandomSource.create(seed ^ index), minX, minZ);
        vanillaPiece.setOrientation(switch (candidate.rotation()) {
            case "north" -> Direction.NORTH;
            case "east" -> Direction.EAST;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            default -> throw new IllegalArgumentException("unsupported test waystation rotation " + candidate.rotation());
        });
        vanillaPiece.move(0, candidate.originY() - vanillaPiece.getBoundingBox().minY(), 0);
        CompoundTag tag = vanillaPiece.createTag(null);
        tag.putInt("HPos", candidate.originY());
        tag.putString("rotation", candidate.rotation());
        tag.putLong("structure_seed", seed);
        tag.putString("LootTable", "minecraft:chests/simple_dungeon");
        tag.putLong("LootTableSeed", seed ^ index);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
            output.flush();
            return new AdventurePlanView.PlannedPiece(candidate.instanceId() + "/piece/" + index,
                    vanillaPiece.getBoundingBox().minX(), vanillaPiece.getBoundingBox().minY(),
                    vanillaPiece.getBoundingBox().minZ(), vanillaPiece.getBoundingBox().maxX(),
                    vanillaPiece.getBoundingBox().maxY(), vanillaPiece.getBoundingBox().maxZ(), bytes.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException("could not freeze test waystation", failure);
        }
    }
}
