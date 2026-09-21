package io.github.luoyan.adventureworldgen.plan;

import io.github.luoyan.adventureworldgen.spatial.TemplateRotation;
import java.util.List;

/** Resource-derived offsets: one fixed orientation or all four native rotation enum values. */
public record TemplateFootprint(List<BoundsXZ> geometry,List<BoundsXZ> exclusion,List<Integer> rotations) {
    public TemplateFootprint(List<BoundsXZ> geometry,List<BoundsXZ> exclusion) {
        this(geometry,exclusion,geometry.size()==1?List.of(0):List.of(0,1,2,3));
    }
    public TemplateFootprint {
        geometry=List.copyOf(geometry);exclusion=List.copyOf(exclusion);rotations=List.copyOf(rotations);
        if((geometry.size()!=1&&geometry.size()!=4)||geometry.size()!=exclusion.size())
            throw new IllegalArgumentException("invalid template orientations");
        if(rotations.size()!=geometry.size()||rotations.stream().anyMatch(r->r<0||r>3)
                ||rotations.size()==4&&!rotations.equals(List.of(0,1,2,3)))throw new IllegalArgumentException("invalid template rotations");
        for(int i=0;i<geometry.size();i++)if(!exclusion.get(i).contains(geometry.get(i)))
            throw new IllegalArgumentException("template exclusion misses geometry");
    }
    public int orientation(long seed,int x,int z) {
        return exclusion.size()==1?0:TemplateRotation.index(seed,x>>4,z>>4);
    }
    public BoundsXZ exclusionAt(long seed,int x,int z) {
        return exclusion.get(orientation(seed,x,z)).translate(x,z);
    }
}
