package io.github.luoyan.adventureworldgen.worldgen.structure;

/** Implemented by the native StructureStart mixin, including starts restored from chunk NBT. */
public interface ExecutionDataHolder {
    StructureExecutionData adventureworldgen$getExecutionData();
    void adventureworldgen$setExecutionData(StructureExecutionData data);
}
