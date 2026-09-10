package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.luoyan.adventureworldgen.spatial.CellMask;

class MultiRegionAreaTest {
    private static final MacroTerrain FLAT=(x,z)->new MacroSample(80,Double.NaN,WaterKind.NONE,false,"r","plains","test");
    private static final ContentId ISLANDS=new ContentId("test:islands");
    private static boolean allowed(ContentId id,int x,int z) {
        if(id.equals(ISLANDS))return x>=32&&x<64&&(z>=32&&z<64||z>=-64&&z<-32);
        return x<16;
    }
    private static AdventureWorldConfig config(int minimum) {
        return new AdventureWorldConfigParser().parse("""
          {"world":{"radius":128},"spawn":{"biome":"test:spawn"},"biomes":{
           "required":[{"id":"test:spawn","adventure_level":0,"area":{"min":1024,"max":1280,"target":1024}},
                       {"id":"test:islands","adventure_level":1,"area":{"min":%d,"max":%d,"target":%d}}],
           "filler":["test:spawn"]}}
          """.formatted(minimum,minimum+256,minimum));
    }
    private static JointPlanner.Result plan(int minimum) {
        return new JointPlanner(PlannerProfile.V2).plan(7331,config(minimum),FLAT,
                (d,x,y,z,s)->{throw new AssertionError("no structures requested");},(l,x,z)->true,MultiRegionAreaTest::allowed);
    }
    @Test void disconnectedRegionsCombineWithoutClaimingTheirIllegalGap() {
        var result=plan(1536);
        var patch=result.patches().stream().filter(p->p.biomeId().equals(ISLANDS)).findFirst().orElseThrow();
        assertTrue(patch.area()>=1536&&patch.area()<=1792);
        assertTrue(Arrays.stream(patch.mask().cells()).anyMatch(c->CellMask.z(c)<0));
        assertTrue(Arrays.stream(patch.mask().cells()).anyMatch(c->CellMask.z(c)>0));
        for(long c:patch.mask().cells())assertTrue(allowed(ISLANDS,CellMask.x(c),CellMask.z(c)));
        var again=plan(1536).patches().stream().filter(p->p.biomeId().equals(ISLANDS)).findFirst().orElseThrow();
        assertEquals(patch.mask(),again.mask(),"recovery must be deterministic");
    }
    @Test void insufficientTotalAreaSurvivesJointValidationWithItsActualOwnership() {
        var result=plan(4096);
        var patch=result.patches().stream().filter(p->p.biomeId().equals(ISLANDS)).findFirst().orElseThrow();
        assertEquals(2048,patch.area(),"retain both complete islands despite the unattainable minimum");
        assertTrue(patch.contains(patch.anchorX(),patch.anchorZ()));
        for(long c:patch.mask().cells())assertTrue(allowed(ISLANDS,CellMask.x(c),CellMask.z(c)));
    }
}
