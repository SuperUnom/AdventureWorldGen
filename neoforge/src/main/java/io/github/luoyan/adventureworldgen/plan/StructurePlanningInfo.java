package io.github.luoyan.adventureworldgen.plan;

import java.util.Objects;
import java.util.List;
import java.util.Comparator;

/** Pure, Minecraft-independent facts a structure type exposes to the planner. */
public record StructurePlanningInfo(ContentId structureId, BoundsXZ footprint, RoadAccess roadAccess, TemplateFootprint templateFootprint) {
    public StructurePlanningInfo(ContentId id) { this(id, null, null, null); }
    public StructurePlanningInfo(ContentId id,BoundsXZ footprint,RoadAccess access) { this(id,footprint,access,null); }
    public BoundsXZ footprintAt(long seed,int x,int z) {
        var declared=footprint==null?null:footprint.translate(x,z);
        if(templateFootprint==null)return declared;
        var resolved=templateFootprint.exclusionAt(seed,x,z);
        return declared==null?resolved:resolved.union(declared);
    }
    public enum Facing {
        NORTH(0,-1), EAST(1,0), SOUTH(0,1), WEST(-1,0);
        public final int dx,dz;
        Facing(int dx,int dz) {this.dx=dx;this.dz=dz;}
    }
    public record AccessPoint(int x,int z,Facing facing) {
        public AccessPoint {
            Objects.requireNonNull(facing);
            if(Math.abs((long)x)>512||Math.abs((long)z)>512)throw new IllegalArgumentException("entrance outside planning envelope");
        }
        public AccessPoint rotate(int turns) {
            int rx=x,rz=z;
            for(int i=0;i<turns;i++){int old=rx;rx=-rz;rz=old;}
            return new AccessPoint(rx,rz,Facing.values()[(facing.ordinal()+turns)%4]);
        }
    }
    public record RoadAccess(int margin,int connectorLength,List<AccessPoint> entrances) {
        public RoadAccess(int margin) {this(margin,16,List.of());}
        public RoadAccess {
            if (margin < 2 || margin > 256) throw new IllegalArgumentException("invalid structure road margin");
            if(connectorLength<8||connectorLength>64||entrances.size()>8)throw new IllegalArgumentException("invalid local connector budget");
            entrances=entrances.stream().sorted(Comparator.comparingInt(AccessPoint::x).thenComparingInt(AccessPoint::z).thenComparing(AccessPoint::facing)).toList();
            var positions=new java.util.HashSet<String>();
            for(var entrance:entrances)if(!positions.add(entrance.x()+","+entrance.z()))throw new IllegalArgumentException("duplicate structure entrance");
        }
    }
    public StructurePlanningInfo {
        Objects.requireNonNull(structureId, "structureId");
        if (footprint != null && (Math.abs((long)footprint.minX()) > 256 || Math.abs((long)footprint.minZ()) > 256
                || Math.abs((long)footprint.maxX()) > 256 || Math.abs((long)footprint.maxZ()) > 256))
            throw new IllegalArgumentException("structure footprint exceeds planning envelope");
    }
}
