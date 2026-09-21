package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.plan.PlannedStructurePlacement;
import io.github.luoyan.adventureworldgen.worldgen.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.util.*;

/** The only conversion from frozen macro anchors to Minecraft execution inputs. */
public final class PlannedStructureBridge {
    private final StructureExecutionCatalog catalog;
    private final Map<Long, List<PlannedStructurePlacement>> byChunk;
    private final List<PlannedStructurePlacement> placements;
    public PlannedStructureBridge(StructureExecutionCatalog catalog, List<PlannedStructurePlacement> placements) {
        this.catalog = catalog;
        this.placements = placements.stream().sorted(Comparator.comparing(PlannedStructurePlacement::instanceId)).toList();
        var index = new HashMap<Long, List<PlannedStructurePlacement>>();
        var instances = new HashSet<String>();
        for (var p : this.placements) {
            var id = ResourceLocation.parse(p.structureId().value());
            if (!catalog.manages(id)) throw new IllegalStateException("unmanaged placement " + p.instanceId());
            if (!instances.add(p.instanceId())) throw new IllegalStateException("duplicate structure instance " + p.instanceId());
            var entries = index.computeIfAbsent(owner(p).toLong(), ignored -> new ArrayList<>());
            if (entries.stream().anyMatch(old -> old.structureId().equals(p.structureId())))
                throw new IllegalStateException("same structure ID has multiple starts in chunk " + owner(p) + ": " + p.structureId());
            entries.add(p);
        }
        var frozen = new HashMap<Long, List<PlannedStructurePlacement>>();
        index.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        byChunk = Map.copyOf(frozen);
    }
    public static ChunkPos owner(PlannedStructurePlacement p) { return new ChunkPos(p.anchorX() >> 4, p.anchorZ() >> 4); }
    public List<PlannedStructurePlacement> placements() { return placements; }
    public boolean manages(ResourceLocation id) { return catalog.manages(id); }

    public void createStarts(RegistryAccess registries, ChunkGeneratorStructureState state, StructureManager manager,
                             ChunkAccess chunk, StructureTemplateManager templates, ChunkGenerator generator) {
        for (var placement : byChunk.getOrDefault(chunk.getPos().toLong(), List.of())) {
            var id = ResourceLocation.parse(placement.structureId().value());
            var structure = registries.registryOrThrow(Registries.STRUCTURE).get(id);
            if (structure == null) throw new IllegalStateException("missing planned structure " + id);
            var existing = chunk.getStartForStructure(structure);
            if (existing != null && existing.isValid()) {
                var data = ((ExecutionDataHolder) (Object) existing).adventureworldgen$getExecutionData();
                if (data == null || !data.instanceId().equals(placement.instanceId()))
                    throw new IllegalStateException("planned start conflicts with existing chunk data: " + placement.instanceId());
                continue;
            }
            try {
                var queryGenerator = generator instanceof AdventureChunkGenerator adventure
                        ? AdventureChunkGenerator.naturalQueries(registries, adventure.profile(),
                            (io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan) adventure.roadPlanView()) : generator;
                var context = new Structure.GenerationContext(registries, queryGenerator, queryGenerator.getBiomeSource(), state.randomState(),
                        templates, state.getLevelSeed(), chunk.getPos(), chunk, structure.biomes()::contains);
                var start = generateStart(structure, context, placement, catalog.terrain(id));
                if (!start.isValid()) throw new IllegalStateException("no valid generation point/pieces (check biome and structure requirements)");
                validateEnvelope(start, chunk);
                if(generator instanceof AdventureChunkGenerator adventure) {
                    var plan=adventure.roadPlanView();
                    for(var r:plan.roads().reservations())if(r.instanceId().equals(placement.instanceId())) {
                        var box=start.getBoundingBox();
                        if(!r.bounds().contains(new io.github.luoyan.adventureworldgen.plan.BoundsXZ(box.minX(),box.minZ(),box.maxX(),box.maxZ())))
                            throw new IllegalStateException("structure violates declared road exclusion envelope: "+r.instanceId());
                    }
                    if(RoadWorldgen.intersects(plan,start.getBoundingBox(),0))throw new IllegalStateException("planned structure intersects frozen road");
                }
                manager.setStartForStructure(SectionPos.bottomOf(chunk), structure, start, chunk);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("structure execution failed: instance=" + placement.instanceId()
                        + ", structure=" + id + ", anchor=" + placement.anchorX() + "," + placement.anchorZ(), failure);
            }
        }
    }

    /** Shared construction path for startup validation and native chunk execution. No starts are cached. */
    public static StructureStart generateStart(Structure structure, Structure.GenerationContext context,
                                               PlannedStructurePlacement placement, TerrainSettings terrain) {
        StructureExecutor executor = structure instanceof TemplateStructure ? new TemplateStructureExecutor()
                : structure instanceof JigsawStructure ? new JigsawStructureExecutor() : new JavaStructureExecutor();
        var start = executor.generate(structure, context, new BlockPos(placement.anchorX(), 0, placement.anchorZ()));
        if (!start.isValid()) return start;
        List<Foundation> supports = List.of();
        if (terrain.mode() == TerrainSettings.Mode.FILL || terrain.mode() == TerrainSettings.Mode.FLATTEN)
            supports = structure instanceof JigsawStructure ? JigsawTerrain.supports(start)
                    : ((TerrainSupportProvider) structure).terrainSupports(start);
        ((ExecutionDataHolder) (Object) start).adventureworldgen$setExecutionData(
                new StructureExecutionData(placement.instanceId(), terrain, supports));
        return start;
    }

    public static void validateEnvelope(StructureStart start, ChunkAccess chunk) {
        validateEnvelope(start, chunk.getPos(), chunk);
    }
    public static void validateEnvelope(StructureStart start, ChunkPos owner, net.minecraft.world.level.LevelHeightAccessor height) {
        var box = start.getBoundingBox();
        if ((box.minX() >> 4) < owner.x - 8 || (box.maxX() >> 4) > owner.x + 8
                || (box.minZ() >> 4) < owner.z - 8 || (box.maxZ() >> 4) > owner.z + 8)
            throw new IllegalStateException("structure/terrain exceeds native 8-chunk reference radius: " + box);
        if (box.minY() < height.getMinBuildHeight() || box.maxY() >= height.getMaxBuildHeight())
            throw new IllegalStateException("structure/terrain exceeds build height: " + box);
    }
}
