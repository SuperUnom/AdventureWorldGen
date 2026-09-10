package io.github.luoyan.adventureworldgen.api;

import io.github.luoyan.adventureworldgen.plan.ContentId;

import java.util.List;

/** Immutable runtime-facing projection of a validated plan-v2 snapshot. */
public interface AdventurePlanView {
    ContentId biomeAt(int blockX, int blockY, int blockZ);
    MacroSample terrainAt(double blockX, double blockZ);
    List<PlannedStructure> structuresIntersecting(int chunkX, int chunkZ);
    SpawnPosition spawnPosition();
    List<ContentId> controlledStructureIds();

    record PlannedStructure(String instanceId, ContentId structureId, int originX, int originY, int originZ,
                            String rotation, int entranceX, int entranceY, int entranceZ,
                            List<StructureAdapter.HorizontalBox> footprint,
                            List<StructureAdapter.HorizontalBox> biomeProtection,
                            List<PlannedPiece> pieces) {
        public PlannedStructure {
            footprint = List.copyOf(footprint);
            biomeProtection = List.copyOf(biomeProtection);
            pieces = List.copyOf(pieces);
        }
        public PlannedStructure(String instanceId, ContentId structureId, int originX, int originY, int originZ,
                                String rotation, List<PlannedPiece> pieces) {
            this(instanceId, structureId, originX, originY, originZ, rotation,
                    originX, originY, originZ,
                    pieces.stream().map(piece -> new StructureAdapter.HorizontalBox(
                            piece.minX(), piece.minZ(), piece.maxX(), piece.maxZ())).toList(),
                    pieces.stream().map(piece -> new StructureAdapter.HorizontalBox(
                            piece.minX(), piece.minZ(), piece.maxX(), piece.maxZ())).toList(), pieces);
        }
    }

    record PlannedPiece(String pieceId, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                        byte[] canonicalNbt) {
        public PlannedPiece { canonicalNbt = canonicalNbt.clone(); }
        @Override public byte[] canonicalNbt() { return canonicalNbt.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof PlannedPiece piece && pieceId.equals(piece.pieceId)
                    && minX == piece.minX && minY == piece.minY && minZ == piece.minZ
                    && maxX == piece.maxX && maxY == piece.maxY && maxZ == piece.maxZ
                    && java.util.Arrays.equals(canonicalNbt, piece.canonicalNbt);
        }
        @Override public int hashCode() {
            int result = java.util.Objects.hash(pieceId, minX, minY, minZ, maxX, maxY, maxZ);
            return 31 * result + java.util.Arrays.hashCode(canonicalNbt);
        }
    }

    record SpawnPosition(double x, double y, double z, float yaw) {}
}
