package io.github.luoyan.adventureworldgen.worldgen;

import com.google.gson.JsonParser;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.plan.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.IOException;
import java.io.Reader;
import java.util.*;

/** Loads pure, explicit road envelopes without inspecting templates or constructing pieces. */
public final class StructureRoadInformation {
    private StructureRoadInformation() {}
    public static StructurePlanningCatalog load(ResourceManager resources, AdventureWorldConfig config) throws IOException {
        return load(config, id -> {
            var location=ResourceLocation.parse(id.value()).withPath("adventureworldgen/structure_planning/"+id.value().split(":")[1]+".json");
            var resource=resources.getResource(location);
            return resource.isPresent()?resource.get().openAsReader():null;
        });
    }
    /** Supplies a reader owned by this loader, or null when no declaration exists. */
    @FunctionalInterface public interface ResourceOpener { Reader open(ContentId id) throws IOException; }
    public static StructurePlanningCatalog load(AdventureWorldConfig config,ResourceOpener resources) throws IOException {
        var result=new ArrayList<StructurePlanningInfo>();
        for(var settings:config.structures()) {
            var id=settings.id();
            StructurePlanningInfo.RoadAccess access=null;
            try(var reader=resources.open(id)) {
                if(reader==null) {result.add(new StructurePlanningInfo(id));continue;}
                var root=JsonParser.parseReader(reader).getAsJsonObject();
                if(!root.keySet().equals(Set.of("road_access")))throw new IllegalArgumentException("expected road_access in "+id);
                var road=root.getAsJsonObject("road_access");
                if(!road.keySet().equals(Set.of("exclusion_radius","approach_distance")))throw new IllegalArgumentException("invalid road access fields in "+id);
                access=new StructurePlanningInfo.RoadAccess(road.get("exclusion_radius").getAsBigDecimal().intValueExact(),road.get("approach_distance").getAsBigDecimal().intValueExact());
            }
            result.add(new StructurePlanningInfo(settings.id(),access));
        }
        return StructurePlanningCatalog.of(result);
    }
}
