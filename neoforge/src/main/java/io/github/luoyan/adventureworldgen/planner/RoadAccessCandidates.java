package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.*;

/** Bounded perimeter/entrance choices with an outward, dry, frozen local connector. */
final class RoadAccessCandidates {
    private RoadAccessCandidates() {}
    record Access(Vec2 endpoint,Vec2 approach) {
        static Access point(Vec2 point) {return new Access(point,point);}
    }
    static List<Access> generate(StructurePlanningInfo info,long seed,PlannedStructurePlacement placement,int width) {
        var bounds=info.footprintAt(seed,placement.anchorX(),placement.anchorZ());
        var road=info.roadAccess();
        var result=new ArrayList<Access>();
        if(!road.entrances().isEmpty()) {
            int rotation=info.templateFootprint()==null?0:info.templateFootprint().rotations().get(
                    info.templateFootprint().orientation(seed,placement.anchorX(),placement.anchorZ()));
            for(var raw:road.entrances()) {
                var entrance=raw.rotate(rotation);
                double x=placement.anchorX()+entrance.x()+.5,z=placement.anchorZ()+entrance.z()+.5;
                double clearance=road.margin()+width/2.0+1;
                boolean outward=switch(entrance.facing()) {
                    case NORTH -> z<bounds.minZ()-clearance;
                    case EAST -> x>bounds.maxX()+1.0+clearance;
                    case SOUTH -> z>bounds.maxZ()+1.0+clearance;
                    case WEST -> x<bounds.minX()-clearance;
                };
                if(outward)result.add(access(x,z,entrance.facing(),road.connectorLength()));
            }
        } else {
            int offset=road.margin()+(int)Math.ceil(width/2.0+1)+1;
            int left=bounds.minX()-offset,right=bounds.maxX()+offset;
            int bottom=bounds.minZ()-offset,top=bounds.maxZ()+offset;
            for(int i=1;i<=3;i++) {
                int x=bounds.minX()+(bounds.maxX()-bounds.minX())*i/4;
                int z=bounds.minZ()+(bounds.maxZ()-bounds.minZ())*i/4;
                result.add(access(left+.5,z+.5,StructurePlanningInfo.Facing.WEST,road.connectorLength()));
                result.add(access(right+.5,z+.5,StructurePlanningInfo.Facing.EAST,road.connectorLength()));
                result.add(access(x+.5,bottom+.5,StructurePlanningInfo.Facing.NORTH,road.connectorLength()));
                result.add(access(x+.5,top+.5,StructurePlanningInfo.Facing.SOUTH,road.connectorLength()));
            }
        }
        return result.stream().distinct().sorted(Comparator.comparingDouble((Access a)->a.endpoint().x()).thenComparingDouble(a->a.endpoint().z())).toList();
    }
    private static Access access(double x,double z,StructurePlanningInfo.Facing facing,int length) {
        return new Access(new Vec2(x,z),new Vec2(x+facing.dx*length,z+facing.dz*length));
    }
}
