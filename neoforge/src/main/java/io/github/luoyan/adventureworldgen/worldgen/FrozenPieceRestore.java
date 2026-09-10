package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rebuilds the pieces an adapter froze into the plan, through the piece types the game registers.
 *
 * <p>Frozen piece NBT carries the type id that {@link StructurePiece#createTag} wrote. This class
 * resolves that id in {@code BuiltInRegistries.STRUCTURE_PIECE} — the same registry vanilla reads in
 * {@code PiecesContainer.load} — and lets the type rebuild its own piece. A structure the shared
 * chunk generator has never heard of is therefore restored by registering its piece type, not by
 * adding a branch to the generator.
 *
 * <p>Vanilla's container loader logs and drops what it cannot rebuild, because a saved chunk must
 * still load. A published plan is a promise, so the same situation is a hard failure here: an
 * unknown or unreadable piece names the type, the piece and the instance instead of letting the
 * structure quietly generate empty.
 */
public final class FrozenPieceRestore {
    private FrozenPieceRestore() {}

    /**
     * Builds the context a piece type may need.
     *
     * <p>{@code createStructures} receives the registries and the template manager but never a
     * {@code ServerLevel}, so the resource manager stays null — the one component with no source at
     * that point. No piece type registered in 1.21.1 reads it: the contextless loader ignores the
     * whole context, and the template and jigsaw loaders use the template manager and the registry
     * access.
     */
    public static StructurePieceSerializationContext context(RegistryAccess registries,
                                                            StructureTemplateManager templates) {
        return new StructurePieceSerializationContext(null, registries, templates);
    }

    /** Restores every frozen piece of one planned structure, in the order the plan stored them. */
    public static List<StructurePiece> restore(AdventurePlanView.PlannedStructure planned,
                                               StructurePieceSerializationContext context) {
        List<StructurePiece> pieces = new ArrayList<>(planned.pieces().size());
        for (var frozen : planned.pieces()) pieces.add(restorePiece(planned.instanceId(), frozen, context));
        return List.copyOf(pieces);
    }

    private static StructurePiece restorePiece(String instanceId, AdventurePlanView.PlannedPiece frozen,
                                               StructurePieceSerializationContext context) {
        CompoundTag tag;
        try {
            tag = readFrozenTag(frozen.canonicalNbt());
        } catch (IllegalStateException unreadable) {
            throw new IllegalStateException("could not read frozen piece " + frozen.pieceId()
                    + " of " + instanceId, unreadable);
        }
        String frozenType = tag.getString("id");
        StructurePieceType pieceType = pieceType(frozenType);
        if (pieceType == null) {
            throw new IllegalStateException("unsupported frozen piece type " + frozenType
                    + " for piece " + frozen.pieceId() + " of " + instanceId
                    + ": no structure piece type is registered under that id");
        }
        try {
            return pieceType.load(context, tag);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("could not restore frozen piece " + frozen.pieceId()
                    + " of " + instanceId + " as " + frozenType, failure);
        }
    }

    /** The piece type id frozen into a piece; throws when the bytes are not readable NBT. */
    public static String frozenPieceType(byte[] canonicalNbt) {
        return readFrozenTag(canonicalNbt).getString("id");
    }

    /** True when this environment registers a piece type under that frozen id. */
    public static boolean registeredPieceType(String frozenType) {
        return pieceType(frozenType) != null;
    }

    private static StructurePieceType pieceType(String frozenType) {
        // Our adapters write the canonical registry key. Vanilla's legacy piece renames are not
        // applied, so an id we did not freeze fails instead of restoring as something else.
        ResourceLocation id = ResourceLocation.tryParse(frozenType.toLowerCase(Locale.ROOT));
        return id == null ? null : BuiltInRegistries.STRUCTURE_PIECE.get(id);
    }

    private static CompoundTag readFrozenTag(byte[] canonicalNbt) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(canonicalNbt))) {
            return NbtIo.read(input);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("could not read frozen piece NBT", failure);
        }
    }
}
