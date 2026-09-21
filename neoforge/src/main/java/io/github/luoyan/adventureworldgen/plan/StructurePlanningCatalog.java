package io.github.luoyan.adventureworldgen.plan;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable lookup of structure-type planning facts; it contains no generation behavior. */
public final class StructurePlanningCatalog {
    private final Map<ContentId, StructurePlanningInfo> entries;

    private StructurePlanningCatalog(Map<ContentId, StructurePlanningInfo> entries) {
        this.entries = Map.copyOf(entries);
    }

    public static StructurePlanningCatalog fromIds(Collection<ContentId> structureIds) {
        var entries = new TreeMap<ContentId, StructurePlanningInfo>();
        for (ContentId id : structureIds) entries.put(id, new StructurePlanningInfo(id));
        return new StructurePlanningCatalog(entries);
    }

    public static StructurePlanningCatalog of(Collection<StructurePlanningInfo> information) {
        var entries = new TreeMap<ContentId, StructurePlanningInfo>();
        for (var info : information) if (entries.put(info.structureId(), info) != null)
            throw new IllegalArgumentException("duplicate structure planning information");
        return new StructurePlanningCatalog(entries);
    }
    public String canonicalIdentity() {
        var result = new StringBuilder();
        new TreeMap<>(entries).forEach((id, info) -> result.append(id.value()).append('=')
                .append(info.roadAccess() == null ? "none" : info.roadAccess().exclusionRadius() + "," + info.roadAccess().approachDistance()).append('\n'));
        return result.toString();
    }
    public Optional<StructurePlanningInfo> find(ContentId structureId) {
        return Optional.ofNullable(entries.get(structureId));
    }
}
