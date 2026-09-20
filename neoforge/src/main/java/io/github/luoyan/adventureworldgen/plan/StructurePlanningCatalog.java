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

    public Optional<StructurePlanningInfo> find(ContentId structureId) {
        return Optional.ofNullable(entries.get(structureId));
    }
}
