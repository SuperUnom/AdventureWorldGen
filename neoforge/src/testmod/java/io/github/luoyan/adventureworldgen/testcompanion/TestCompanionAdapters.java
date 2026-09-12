package io.github.luoyan.adventureworldgen.testcompanion;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.BiomeAdapter;
import io.github.luoyan.adventureworldgen.api.MacroSample;
import io.github.luoyan.adventureworldgen.api.MacroTerrain;
import io.github.luoyan.adventureworldgen.api.StructureAdapter;
import io.github.luoyan.adventureworldgen.api.WaterKind;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.core.Direction;

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
        @Override public ContentId biomeId() { return ASHEN_GROVE_ID; }
        @Override public String adapterVersion() { return "testcompanion-ashen-grove-v1"; }
        @Override public Compatibility compatibility(MacroSample terrain) {
            boolean allowed = terrain.waterKind() == WaterKind.NONE && !terrain.hazardous();
            return new Compatibility(allowed, allowed ? 0.75 : 0.0, allowed ? "dry custom biome" : "requires dry terrain");
        }
    };

    public static final StructureAdapter WAYSTATION = new StructureAdapter() {
        @Override public ContentId structureId() { return WAYSTATION_ID; }
        // v2 freezes WaystationPiece instead of a vanilla pyramid piece. The version is part of the
        // plan identity, so an old companion READY reloads as a miss and is planned again; the
        // production adapter set is untouched by this bump.
        @Override public String adapterVersion() { return "testcompanion-waystation-v2"; }
        @Override public Descriptor describe() {
            return new Descriptor(List.of("north", "east", "south", "west"), 32.0, true, true);
        }
        @Override public Prepared prepare(Candidate candidate, long structureSeed) {
            Direction orientation = switch (candidate.rotation()) {
                case "north" -> Direction.NORTH;
                case "east" -> Direction.EAST;
                case "south" -> Direction.SOUTH;
                case "west" -> Direction.WEST;
                default -> throw new IllegalArgumentException("unsupported test waystation rotation " + candidate.rotation());
            };
            int variant = Math.floorMod((int) structureSeed, WaystationPiece.VARIANTS);
            List<AdventurePlanView.PlannedPiece> pieces = new ArrayList<>();
            // The two boxes stay disjoint: pieces are placed in order and a shared cell would let the
            // later piece overwrite the earlier one's floor, wall or container.
            pieces.add(freeze(new WaystationPiece(0, variant, structureSeed, WaystationPiece.SIMPLE_DUNGEON_LOOT,
                    orientation, box(candidate, -12, -6, 12, 6)), candidate, 0));
            pieces.add(freeze(new WaystationPiece(1, variant, structureSeed ^ 1L, WaystationPiece.SIMPLE_DUNGEON_LOOT,
                    orientation, box(candidate, 14, -4, 34, 4)), candidate, 1));
            List<HorizontalBox> footprint = pieces.stream().map(piece -> new HorizontalBox(
                    piece.minX(), piece.minZ(), piece.maxX(), piece.maxZ())).toList();
            return new Prepared(candidate, pieces, footprint,
                    List.of(new HorizontalBox(candidate.originX() - 16, candidate.originZ() - 10,
                            candidate.originX() + 38, candidate.originZ() + 16)),
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
    };

    private TestCompanionAdapters() {}

    /** The piece's world box, anchored so both waystation pieces keep the plan's origin inside them. */
    private static net.minecraft.world.level.levelgen.structure.BoundingBox box(
            StructureAdapter.Candidate candidate, int offsetX1, int offsetZ1, int offsetX2, int offsetZ2) {
        return new net.minecraft.world.level.levelgen.structure.BoundingBox(
                candidate.originX() + offsetX1, candidate.originY(), candidate.originZ() + offsetZ1,
                candidate.originX() + offsetX2, candidate.originY() + WaystationPiece.HEIGHT - 1,
                candidate.originZ() + offsetZ2);
    }

    private static AdventurePlanView.PlannedPiece freeze(WaystationPiece piece,
                                                         StructureAdapter.Candidate candidate, int index) {
        CompoundTag tag = piece.createTag(null);
        var bounds = piece.getBoundingBox();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
            output.flush();
            return new AdventurePlanView.PlannedPiece(candidate.instanceId() + "/piece/" + index,
                    bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                    bytes.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException("could not freeze test waystation piece", failure);
        }
    }
}
