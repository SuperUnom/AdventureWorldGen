package io.github.luoyan.adventureworldgen.config;

import com.google.gson.JsonParser;
import io.github.luoyan.adventureworldgen.plan.*;
import java.io.IOException;
import java.io.Reader;
import java.util.*;

/** Strict, game-independent parser for structure planning declarations. */
public final class StructurePlanningJson {
    private StructurePlanningJson() {}
    /** Supplies a reader owned by this loader, or null when no declaration exists. */
    @FunctionalInterface public interface ResourceOpener { Reader open(ContentId id) throws IOException; }
    public static StructurePlanningCatalog load(AdventureWorldConfig config,ResourceOpener resources) throws IOException {
        var result=new ArrayList<StructurePlanningInfo>();
        for(var settings:config.structures()) {
            var id=settings.id();
            try(var reader=resources.open(id)) {
                if(reader==null) {result.add(new StructurePlanningInfo(id));continue;}
                var root=JsonParser.parseReader(reader).getAsJsonObject();
                if(!Set.of("footprint","road_access").containsAll(root.keySet()))
                    throw new IllegalArgumentException("invalid structure planning fields in "+id);
                BoundsXZ footprint=null;
                StructurePlanningInfo.RoadAccess access=null;
                if(root.has("footprint")) {
                    var box=root.getAsJsonObject("footprint");
                    if(!box.keySet().equals(Set.of("min_x","min_z","max_x","max_z")))
                        throw new IllegalArgumentException("invalid footprint fields in "+id);
                    footprint=new BoundsXZ(integer(box,"min_x"),integer(box,"min_z"),integer(box,"max_x"),integer(box,"max_z"));
                }
                if(root.has("road_access")) {
                    var road=root.getAsJsonObject("road_access");
                    if(road.keySet().equals(Set.of("exclusion_radius","approach_distance"))) {
                        if(footprint!=null)throw new IllegalArgumentException("cannot combine legacy envelope and footprint: "+id);
                        int radius=integer(road,"exclusion_radius"),distance=integer(road,"approach_distance");
                        if(radius<1||radius>128||distance<radius+8||distance>256)
                            throw new IllegalArgumentException("invalid legacy road envelope: "+id);
                        footprint=new BoundsXZ(-radius,-radius,radius,radius);
                        access=new StructurePlanningInfo.RoadAccess(distance-radius);
                    } else {
                        if(!Set.of("margin","connector_length","entrances").containsAll(road.keySet())||!road.has("margin"))
                            throw new IllegalArgumentException("invalid road access fields in "+id);
                        var entrances=new ArrayList<StructurePlanningInfo.AccessPoint>();
                        if(road.has("entrances"))for(var element:road.getAsJsonArray("entrances")) {
                            var point=element.getAsJsonObject();
                            if(!point.keySet().equals(Set.of("x","z","facing")))throw new IllegalArgumentException("invalid entrance in "+id);
                            var facing=point.get("facing");
                            if(!facing.isJsonPrimitive()||!facing.getAsJsonPrimitive().isString()
                                    ||!Set.of("north","east","south","west").contains(facing.getAsString()))
                                throw new IllegalArgumentException("invalid entrance facing in "+id);
                            entrances.add(new StructurePlanningInfo.AccessPoint(integer(point,"x"),integer(point,"z"),
                                    StructurePlanningInfo.Facing.valueOf(facing.getAsString().toUpperCase(Locale.ROOT))));
                        }
                        access=new StructurePlanningInfo.RoadAccess(integer(road,"margin"),road.has("connector_length")?integer(road,"connector_length"):16,entrances);
                    }
                }
                result.add(new StructurePlanningInfo(id,footprint,access));
            }
        }
        return StructurePlanningCatalog.of(result);
    }
    private static int integer(com.google.gson.JsonObject object,String key) {
        var value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("expected integer "+key);
        return value.getAsBigDecimal().intValueExact();
    }
}
