package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StructureBiomePlanningTest {
    private static final MacroTerrain FLAT=(x,z)->new MacroSample(80,Double.NaN,WaterKind.NONE,false,"r","plains","test");
    private AdventureWorldConfig config() {
        return new AdventureWorldConfigParser().parse("""
          {"world":{"radius":512},"spawn":{"biome":"test:plains"},"biomes":{
           "required":[{"id":"test:forest","adventure_level":2,"area":{"min":4096,"target":65536}}],
           "filler":["test:plains"],"terrain_rules":{"test:forbidden":{"allowed_terrain":["mountains"]}}},
           "structures":[{"id":"test:keep","adventure_level":4,"count":{"min":1,"max":1},
           "allowed_biomes":{"id":["test:forbidden","test:desert"],"area":{"min":4096,"target":65536}},"entrance":[0,0,0]}]}
          """);
    }
    private JointPlanner.Result plan(int halfWidth,List<String> events) {
        return new JointPlanner(PlannerProfile.V2).plan(7331,config(),FLAT,(d,x,y,z,seed)-> {
            assertTrue(events.contains("biomes"),"structure was prepared before required biome growth finished");
            assertEquals(List.of(new ContentId("test:desert")),d.allowedBiomes(),"adapter did not receive selected carrier biome");
            events.add("freeze");
            return new AdventurePlanView.PlannedStructure(d.instanceId(),d.structureId(),x,y,z,"north",
                    List.of(new AdventurePlanView.PlannedPiece(d.instanceId()+"/0",x-halfWidth,y,z-halfWidth,x+halfWidth,y+8,z+halfWidth,new byte[]{1})));
        },(level,x,z)->true,(biome,x,z)->true,ignored->{},events::add);
    }
    @Test void growsAllBiomesBeforePlacingStructuresAndDoesNotChangeLayoutToFitFootprints() {
        var first=plan(4,new ArrayList<>());var second=plan(12,new ArrayList<>());
        assertEquals(first.patches(),second.patches(),"structure geometry changed already planned biome ownership");
        var carrier=first.patches().stream().filter(p->p.patchId().equals(StableIds.carrierPatch(first.structures().getFirst().instanceId()))).findFirst().orElseThrow();
        assertNotNull(carrier.mask());
        assertTrue(carrier.area()>65536,"carrier stopped at target unlike ordinary necessary biomes");
        assertTrue(carrier.area()<(long)(carrier.maxXExclusive()-carrier.minX())*(carrier.maxZExclusive()-carrier.minZ()),"carrier is still a fixed rectangle");
        for(var structure:second.structures())for(var box:structure.biomeProtection())
            for(int x=box.minX();x<=box.maxX();x++)for(int z=box.minZ();z<=box.maxZ();z++)assertTrue(carrier.contains(x,z));
        var remaining=new HashSet<Long>();for(long c:carrier.mask().cells())remaining.add(c);
        var queue=new ArrayDeque<Long>();queue.add(remaining.iterator().next());remaining.remove(queue.peek());
        while(!queue.isEmpty()){long c=queue.remove();for(int[] d:new int[][]{{4,0},{-4,0},{0,4},{0,-4}}){long next=CellMask.key(CellMask.x(c)+d[0],CellMask.z(c)+d[1]);if(remaining.remove(next))queue.add(next);}}
        assertTrue(remaining.isEmpty());
    }
    @Test void reportsAnUnplaceableStructureInsteadOfOverwritingItsBiomeOrOmittingIt() {
        var error=assertThrows(PlanningFailure.class,()->plan(1024,new ArrayList<>()));
        assertTrue(error.getMessage().contains("structure-in-biome"));
        assertTrue(error.getMessage().contains("test:keep"));
    }
    @Test void smallCarrierRetainsUsableInteriorOnUnevenTerrain() {
        var c=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":512},"spawn":{"biome":"test:plains"},
           "biomes":{"required":[],"filler":["test:plains"]},
           "structures":[{"id":"test:keep","adventure_level":4,"count":{"min":1,"max":1},
           "allowed_biomes":{"id":["test:desert"],"area":{"min":4096,"max":8192}},"entrance":[0,0,0]}]}
          """);
        MacroTerrain uneven=(x,z)->new MacroSample(90+12*Math.sin(x/120)+7*Math.cos(z/80),
                Double.NaN,WaterKind.NONE,false,"r","hills","test");
        for(long seed:new long[]{9,7331,8844}) {
            var result=new JointPlanner(PlannerProfile.V2).plan(seed,c,uneven,(d,x,y,z,s)->
                    new AdventurePlanView.PlannedStructure(d.instanceId(),d.structureId(),x,y,z,"north",
                            List.of(new AdventurePlanView.PlannedPiece(d.instanceId()+"/0",x-16,y,z-10,x+29,y+8,z+16,new byte[]{1}))));
            var carrier=result.patches().stream().filter(p->p.patchId().startsWith("patch/carrier/")).findFirst().orElseThrow();
            assertTrue(carrier.area()>=4096&&carrier.area()<=8192);
            var structure=result.structures().getFirst();
            for(int x=structure.originX()-16;x<=structure.originX()+29;x++)
                for(int z=structure.originZ()-10;z<=structure.originZ()+16;z++)assertTrue(carrier.contains(x,z));
        }
    }
}
