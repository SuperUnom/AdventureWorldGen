package io.github.luoyan.adventureworldgen.plan;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable lookup of structure-type planning facts; it contains no generation behavior. */
public final class StructurePlanningCatalog {
    private final Map<ContentId, StructurePlanningInfo> entries;
    private final Map<String, StructurePlanningInfo> instances;

    private StructurePlanningCatalog(Map<ContentId, StructurePlanningInfo> entries) {
        this(entries, Map.of());
    }
    private StructurePlanningCatalog(Map<ContentId, StructurePlanningInfo> entries, Map<String, StructurePlanningInfo> instances) {
        this.entries = Map.copyOf(entries);
        this.instances = Map.copyOf(instances);
    }

    /** Derived facts for this run, never another source of author configuration. */
    public StructurePlanningCatalog withInstances(Map<String, StructurePlanningInfo> resolved) {
        return new StructurePlanningCatalog(entries, resolved);
    }
    public Optional<StructurePlanningInfo> find(PlannedStructurePlacement placement) {
        var resolved = instances.get(placement.instanceId());
        if (resolved != null && !resolved.structureId().equals(placement.structureId()))
            throw new IllegalArgumentException("instance structure ID mismatch");
        return resolved == null ? find(placement.structureId()) : Optional.of(resolved);
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
                .append(info.footprint()).append(';').append(info.roadAccess()).append(';').append(info.templateFootprint()).append('\n'));
        return result.toString();
    }
    public Optional<StructurePlanningInfo> find(ContentId structureId) {
        return Optional.ofNullable(entries.get(structureId));
    }
}
