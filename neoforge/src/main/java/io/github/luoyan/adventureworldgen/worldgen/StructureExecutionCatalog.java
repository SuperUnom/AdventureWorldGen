package io.github.luoyan.adventureworldgen.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.luoyan.adventureworldgen.worldgen.structure.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Immutable per-generator execution configuration, built before its first chunk request. */
public final class StructureExecutionCatalog {
    private final Map<ResourceLocation, TerrainSettings> managed;
    public StructureExecutionCatalog(Map<ResourceLocation, TerrainSettings> managed) { this.managed = Map.copyOf(managed); }
    public Set<ResourceLocation> ids() { return managed.keySet(); }
    public boolean manages(ResourceLocation id) { return managed.containsKey(id); }
    public TerrainSettings terrain(ResourceLocation id) {
        var value = managed.get(id);
        if (value == null) throw new IllegalStateException("unmanaged planned structure " + id);
        return value;
    }

    public static StructureExecutionCatalog load(Set<ResourceLocation> ids, RegistryAccess registries,
                                                 ResourceManager resources, StructureTemplateManager templates) {
        var entries = new HashMap<ResourceLocation, TerrainSettings>();
        var structures = registries.registryOrThrow(Registries.STRUCTURE);
        for (var id : ids.stream().sorted().toList()) {
            Structure structure = structures.get(id);
            if (structure == null) throw new IllegalStateException("missing structure " + id);
            var terrain = structure instanceof TemplateStructure template ? template.foundationSettings() : TerrainSettings.NATIVE;
            var resource = resources.getResource(id.withPath("adventureworldgen/structure_execution/" + id.getPath() + ".json"));
            if (resource.isPresent()) {
                try (var reader = resource.get().openAsReader()) {
                    var json = JsonParser.parseReader(reader).getAsJsonObject();
                    if (!Set.of("mode", "margin").containsAll(json.keySet()))
                        throw new IllegalStateException("unknown structure execution fields for " + id);
                    terrain = TerrainSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
                } catch (IOException failure) { throw new IllegalStateException("reading structure execution " + id, failure); }
            }
            validate(structure, terrain, templates);
            entries.put(id, terrain);
        }
        return new StructureExecutionCatalog(entries);
    }

    public static void validate(Structure structure, TerrainSettings terrain, StructureTemplateManager templates) {
        if (structure instanceof TemplateStructure template) template.validateTemplate(templates);
        if (terrain.mode() == TerrainSettings.Mode.FILL || terrain.mode() == TerrainSettings.Mode.FLATTEN) {
            if (!(structure instanceof JigsawStructure) && !(structure instanceof TerrainSupportProvider))
                throw new IllegalStateException("Java structure must implement TerrainSupportProvider for fill/flatten: " + structure.type());
        }
        if (structure instanceof JigsawParameters p && p.adventureworldgen$startPool().value().size() == 0)
            throw new IllegalStateException("empty jigsaw start pool");
    }
}
