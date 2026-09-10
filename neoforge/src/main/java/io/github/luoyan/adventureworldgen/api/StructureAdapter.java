package io.github.luoyan.adventureworldgen.api;

import io.github.luoyan.adventureworldgen.plan.ContentId;

import java.util.List;

/** Structure implementations must freeze all random choices before plan publication. */
public interface StructureAdapter {
    ContentId structureId();
    String adapterVersion();
    Descriptor describe();
    Prepared prepare(Candidate candidate, long structureSeed);
    List<String> validatePrepared(Prepared structure, MacroTerrain terrain);
    byte[] serializePieces(Prepared structure);

    /** Must be idempotent for the same (instance,piece,chunk) key. */
    void placeChunk(Prepared structure, int chunkX, int chunkZ, PlacementTarget target);

    record Descriptor(List<String> rotations, double maximumFootprintRadius,
                      boolean canFreezeAllPieces, boolean requiresSupportPatch) {
        public Descriptor { rotations = List.copyOf(rotations); }
    }

    record Candidate(String instanceId, int originX, int originY, int originZ, String rotation) {}
    record Prepared(Candidate candidate, List<AdventurePlanView.PlannedPiece> pieces,
                    List<HorizontalBox> footprint, List<HorizontalBox> biomeProtection,
                    int entranceX, int entranceY, int entranceZ) {
        public Prepared {
            pieces = List.copyOf(pieces);
            footprint = List.copyOf(footprint);
            biomeProtection = List.copyOf(biomeProtection);
        }
    }
    record HorizontalBox(int minX, int minZ, int maxX, int maxZ) {}

    interface PlacementTarget {
        boolean beginOnce(String instanceId, String pieceId, int chunkX, int chunkZ);
        void placeCanonicalPiece(AdventurePlanView.PlannedPiece piece);
    }
}
