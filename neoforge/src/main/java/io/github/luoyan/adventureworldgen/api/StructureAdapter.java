package io.github.luoyan.adventureworldgen.api;

import io.github.luoyan.adventureworldgen.plan.ContentId;

import java.util.List;

/**
 * Structure implementations must freeze all random choices before plan publication.
 *
 * <p>There is exactly one execution path for a planned structure, and this interface covers its
 * first half: an adapter turns a demand into frozen pieces ({@link Prepared#pieces()}), the plan
 * stores their canonical NBT, and the Minecraft side rebuilds them through the registered piece
 * types before injecting a vanilla {@code StructureStart}. Placement is then Minecraft's own chunk
 * pipeline, so an adapter neither places blocks nor keeps per-chunk commit state.
 */
public interface StructureAdapter {
    ContentId structureId();
    String adapterVersion();
    Descriptor describe();
    Prepared prepare(Candidate candidate, long structureSeed);
    List<String> validatePrepared(Prepared structure, MacroTerrain terrain);

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
}
