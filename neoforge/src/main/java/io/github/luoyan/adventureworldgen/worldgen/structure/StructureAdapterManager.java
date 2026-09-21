package io.github.luoyan.adventureworldgen.worldgen.structure;

import net.minecraft.server.level.ServerLevel;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Resolves a placement and dispatches it to the adapter for the detected runtime type. */
public final class StructureAdapterManager {
    private final StructureResolver resolver;
    private final Map<StructureType, StructureAdapter> adapters;

    public StructureAdapterManager() {
        this(new StructureResolver(), Map.of(
                StructureType.TEMPLATE, new TemplateAdapter(),
                StructureType.JIGSAW, new JigsawAdapter(),
                StructureType.JAVA_STRUCTURE, new JavaStructureAdapter()));
    }

    public StructureAdapterManager(StructureResolver resolver, Map<StructureType, StructureAdapter> adapters) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(adapters, "adapters");
        var copy = new EnumMap<StructureType, StructureAdapter>(StructureType.class);
        adapters.forEach((type, adapter) -> copy.put(
                Objects.requireNonNull(type, "adapter type"),
                Objects.requireNonNull(adapter, "adapter")));
        copy.remove(StructureType.UNKNOWN);
        this.adapters = Map.copyOf(copy);
    }

    public void generate(ServerLevel level, StructurePlacement placement) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(placement, "placement");

        StructureType type = resolver.resolve(level, placement);
        if (type == StructureType.UNKNOWN) {
            throw new IllegalArgumentException("No structure template or registered Structure exists for "
                    + placement.structureId());
        }
        StructureAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new IllegalStateException("No adapter is registered for structure type " + type);
        }
        adapter.generate(level, placement);
    }
}
