package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.api.AdventurePlanView;
import io.github.luoyan.adventureworldgen.api.FrozenPieceSupport;

import java.util.Optional;

/**
 * The production answer: a frozen piece is restorable when the game registers its piece type.
 *
 * <p>It uses the same lookup as the restore path, so "the check accepted it" and "the chunk
 * generator can rebuild it" cannot drift apart. A companion mod registers its own type and is
 * covered without the core learning its name.
 */
public final class RegisteredPieceSupport implements FrozenPieceSupport {
    public static final RegisteredPieceSupport INSTANCE = new RegisteredPieceSupport();
    private RegisteredPieceSupport() {}

    @Override
    public Optional<UnsupportedPiece> firstUnsupported(AdventurePlanView.PlannedStructure structure) {
        for (var piece : structure.pieces()) {
            String type;
            try {
                type = FrozenPieceRestore.frozenPieceType(piece.canonicalNbt());
            } catch (RuntimeException unreadable) {
                return Optional.of(new UnsupportedPiece(piece.pieceId(), "<unreadable frozen NBT>"));
            }
            if (FrozenPieceRestore.registeredPieceType(type)) continue;
            return Optional.of(new UnsupportedPiece(piece.pieceId(), type));
        }
        return Optional.empty();
    }
}
