package io.github.luoyan.adventureworldgen.worldgen;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.util.ArrayList;

/** Vanilla weighted candidate loop with managed entries removed before selection. */
final class NativeStructureCandidates {
    private NativeStructureCandidates() {}
    static void create(RegistryAccess registries, ChunkGeneratorStructureState state, StructureManager manager,
                       ChunkAccess chunk, StructureTemplateManager templates, ChunkGenerator generator, PlannedStructureBridge planned) {
        var registry = registries.registryOrThrow(Registries.STRUCTURE);
        for (var holder : state.possibleStructureSets()) {
            var set = holder.value();
            var candidates = new ArrayList<>(set.structures().stream()
                    .filter(entry -> !planned.manages(registry.getKey(entry.structure().value()))).toList());
            if (candidates.isEmpty()) continue;
            if (candidates.stream().anyMatch(entry -> {
                var start = chunk.getStartForStructure(entry.structure().value());
                return start != null && start.isValid();
            })) continue;
            var pos = chunk.getPos();
            if (!set.placement().isStructureChunk(state, pos.x, pos.z)) continue;
            if (candidates.size() == 1) {
                tryGenerate(candidates.getFirst(), registries, state, manager, chunk, templates, generator);
                continue;
            }
            var random = new WorldgenRandom(new LegacyRandomSource(0));
            random.setLargeFeatureSeed(state.getLevelSeed(), pos.x, pos.z);
            int total = candidates.stream().mapToInt(StructureSet.StructureSelectionEntry::weight).sum();
            while (!candidates.isEmpty()) {
                int choice = random.nextInt(total), index = 0;
                for (; index < candidates.size(); index++) {
                    choice -= candidates.get(index).weight();
                    if (choice < 0) break;
                }
                var entry = candidates.remove(index);
                if (tryGenerate(entry, registries, state, manager, chunk, templates, generator)) break;
                total -= entry.weight();
            }
        }
    }
    private static boolean tryGenerate(StructureSet.StructureSelectionEntry entry, RegistryAccess registries,
                                       ChunkGeneratorStructureState state, StructureManager manager, ChunkAccess chunk,
                                       StructureTemplateManager templates, ChunkGenerator generator) {
        var structure = entry.structure().value();
        var existing = chunk.getStartForStructure(structure);
        var start = structure.generate(registries, generator, generator.getBiomeSource(), state.randomState(), templates,
                state.getLevelSeed(), chunk.getPos(), existing == null ? 0 : existing.getReferences(), chunk, structure.biomes()::contains);
        if (!start.isValid()) return false;
        if(generator instanceof AdventureChunkGenerator adventure && RoadWorldgen.intersects(adventure.roadPlanView(),start.getBoundingBox(),StructureTerrain.NATIVE_MARGIN))return false;
        manager.setStartForStructure(SectionPos.bottomOf(chunk), structure, start, chunk);
        return true;
    }
}
