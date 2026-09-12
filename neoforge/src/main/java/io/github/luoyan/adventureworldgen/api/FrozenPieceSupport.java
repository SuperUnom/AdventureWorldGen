package io.github.luoyan.adventureworldgen.api;

import java.util.Optional;

/**
 * Whether this environment can rebuild the pieces an adapter froze into a plan.
 *
 * <p>Planning asks before it publishes a plan and again right after it decodes one, so a piece type
 * this environment does not have fails there - with the structure, the instance, the piece and the
 * type named - instead of generating a structure without its pieces deep inside chunk generation.
 *
 * <p>Frozen NBT is the only source of truth for the type. Nothing else lists piece types: not
 * {@link StructureAdapter.Descriptor}, not the plan format, not an adapter version. The answer is a
 * property of the runtime, so it takes no part in the plan input identity.
 */
@FunctionalInterface
public interface FrozenPieceSupport {
    /** The first frozen piece of this structure that cannot be rebuilt, or empty when all can. */
    Optional<UnsupportedPiece> firstUnsupported(AdventurePlanView.PlannedStructure structure);

    /** A frozen piece this environment cannot rebuild, named the way the plan stores it. */
    record UnsupportedPiece(String pieceId, String pieceType) {}
}
