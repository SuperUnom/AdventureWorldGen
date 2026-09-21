package io.github.luoyan.adventureworldgen.plan;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StructureFootprintTest {
    @Test void resourceGeometryAndInfluenceBothEnterIdentityAndResolveAtNegativeAnchors() {
        var id=new ContentId("test:template");
        var geometry=new BoundsXZ(0,0,30,6);
        var first=new TemplateFootprint(List.of(geometry),List.of(geometry.expand(8)));
        var second=new TemplateFootprint(List.of(geometry),List.of(geometry.expand(12)));
        var a=new StructurePlanningInfo(id,null,new StructurePlanningInfo.RoadAccess(4),first);
        var b=new StructurePlanningInfo(id,null,new StructurePlanningInfo.RoadAccess(4),second);
        assertNotEquals(StructurePlanningCatalog.of(List.of(a)).canonicalIdentity(),StructurePlanningCatalog.of(List.of(b)).canonicalIdentity());
        assertEquals(geometry.expand(8).translate(-17,-33),a.footprintAt(32,-17,-33));
        var resized=new StructurePlanningInfo(id,null,a.roadAccess(),new TemplateFootprint(List.of(new BoundsXZ(0,0,20,6)),first.exclusion()));
        assertNotEquals(StructurePlanningCatalog.of(List.of(a)).canonicalIdentity(),StructurePlanningCatalog.of(List.of(resized)).canonicalIdentity());
    }
    @Test void malformedBoundsAndTemplateGeometryAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new BoundsXZ(5,0,2,4));
        var box=new BoundsXZ(-10,-20,40,30);
        assertThrows(IllegalArgumentException.class,()->new TemplateFootprint(List.of(box),List.of(new BoundsXZ(0,0,2,2))));
        assertThrows(IllegalArgumentException.class,()->new TemplateFootprint(List.of(box,box),List.of(box,box)));
    }
}
