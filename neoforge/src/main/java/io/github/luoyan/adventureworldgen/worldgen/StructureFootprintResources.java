package io.github.luoyan.adventureworldgen.worldgen;

import io.github.luoyan.adventureworldgen.config.LoadedProfile;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.worldgen.structure.TemplateStructure;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.util.ArrayList;

/** Converts active Minecraft resources into immutable facts before calculating the plan identity. */
public final class StructureFootprintResources {
    private StructureFootprintResources() {}
    public static LoadedProfile resolve(LoadedProfile loaded,RegistryAccess registries,
                                        StructureTemplateManager templates,StructureExecutionCatalog execution) {
        var information=new ArrayList<StructurePlanningInfo>();
        for(var requested:loaded.config().structures()) {
            var id=requested.id();
            var info=loaded.structurePlanning().find(id).orElse(new StructurePlanningInfo(id));
            var key=ResourceLocation.parse(id.value());
            var structure=registries.registryOrThrow(Registries.STRUCTURE).get(key);
            if(structure instanceof TemplateStructure template) {
                var values=template.planningBounds(templates,execution.terrain(key));
                var footprint=new TemplateFootprint(values.stream().map(b->bounds(b.geometry())).toList(),
                        values.stream().map(b->bounds(b.exclusion())).toList(),values.stream().map(TemplateStructure.PlanningBounds::rotation).toList());
                info=new StructurePlanningInfo(id,info.footprint(),info.roadAccess(),footprint);
            }
            information.add(info);
        }
        return new LoadedProfile(loaded.id(),loaded.config(),loaded.canonicalJson(),loaded.sourcePack(),StructurePlanningCatalog.of(information));
    }
    private static BoundsXZ bounds(BoundingBox box) { return new BoundsXZ(box.minX(),box.minZ(),box.maxX(),box.maxZ()); }
}
