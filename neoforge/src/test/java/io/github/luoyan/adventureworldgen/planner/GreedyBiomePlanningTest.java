package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.luoyan.adventureworldgen.spatial.CellMask;
import io.github.luoyan.adventureworldgen.plan.ContentId;

class GreedyBiomePlanningTest {
    private static final MacroTerrain FLAT=(x,z)->new MacroSample(80,Double.NaN,WaterKind.NONE,false,"r","plains","test");

    @Test void scarceTerrainSeedsBeforeCommonTerrainIncludingStructureCarriers() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":512},"spawn":{"biome":"test:spawn"},"biomes":{
           "required":[{"id":"test:common","adventure_level":1,"area":{"min":1024,"max":4096}},
                       {"id":"test:rare","adventure_level":9,"area":{"min":1024,"max":4096}}],
           "filler":["test:spawn"],"terrain_rules":{
             "test:rare":{"allowed_templates":["badlands"]},
             "test:carrier":{"allowed_templates":["badlands"]}}},
           "structures":[{"id":"test:keep","adventure_level":8,"count":{"min":1,"max":1},
             "allowed_biomes":{"id":["test:carrier"],"area":{"min":1024,"max":4096}},"entrance":[0,0,0]}]}
          """);
        MacroTerrain terrain=(x,z)->x>160&&x<320?new MacroSample(80,Double.NaN,WaterKind.NONE,false,
                "rare","plateau","test","badlands","",0,0,0,0,0):FLAT.sample(x,z);
        var index=new PlacementIndex(config,terrain,(level,x,z)->true,(id,x,z)->true);
        var demands=new RequirementExpander().expandMinimum(config).patches();
        var result=new BiomeAllocationPlanner().allocate(9,config,index,demands,List.of());
        var ids=result.patches().stream().map(p->p.biomeId().value()).toList();
        assertEquals("test:spawn",ids.getFirst());
        assertTrue(ids.indexOf("test:rare")<ids.indexOf("test:common"));
        assertTrue(ids.indexOf("test:carrier")<ids.indexOf("test:common"));
        assertTrue(index.terrainCapacity(List.of(new ContentId("test:rare")))
                <index.terrainCapacity(List.of(new ContentId("test:common"))));
    }

    @Test void seedsAndGrowthStayInsideConfiguredTemperatureAndReportMissingSupply() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":384},"spawn":{"biome":"test:spawn"},"biomes":{
           "required":[{"id":"test:hot","adventure_level":5,"area":{"min":4096,"max":8192}}],
           "filler":["test:spawn","test:hot"],"terrain_rules":{"test:hot":{"temperatures":{"hot":1}}}}}
          """);
        var climate=new ClimatePlan(7331,config,FLAT);
        var demands=new RequirementExpander().expandMinimum(config).patches();
        var result=new BiomeAllocationPlanner().allocate(7331,config,
                new PlacementIndex(config,FLAT,(l,x,z)->true,(id,x,z)->true),demands,List.of(),climate,v->{});
        var hot=result.patches().stream().filter(p->p.biomeId().value().equals("test:hot")).findFirst().orElseThrow();
        assertTrue(climate.prefersType(hot.biomeId(),hot.anchorX()+2,hot.anchorZ()+2,FLAT.sample(0,0)));
        long preferred=Arrays.stream(hot.mask().cells()).filter(c->climate.prefersType(hot.biomeId(),
                CellMask.x(c)+2,CellMask.z(c)+2,FLAT.sample(0,0))).count();
        assertEquals(hot.mask().size(),preferred,"every claimed cell must satisfy its configured temperature");
        var s=climate.snapshot();
        var medium=new ClimatePlan(7331,config,FLAT,v->{},new ClimatePlan.State(s.extent(),s.slopeHeight(),s.regionalHeight(),
                s.angle(),-101,101,new double[]{-100,-99,100},false,AdventureWorldConfig.TemperatureType.MEDIUM,
                s.ratios(),s.actual(),List.of(),s.supply(),s.humidity()));
        var relaxed=new BiomeAllocationPlanner().allocate(7331,config,
                new PlacementIndex(config,FLAT,(l,x,z)->true,(id,x,z)->true),demands,List.of(),medium,v->{});
        assertFalse(relaxed.patches().stream().anyMatch(p->p.biomeId().equals(hot.biomeId())),
                "zero hot supply must relax area, never temperature admission");
    }

    @Test void quartCachePreservesNegativeCoordinatesAndComputesEachFrozenValueOnce() {
        var field=new FrozenQuartField(512);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        for(int repeat=0;repeat<3;repeat++)for(int x:new int[]{-130,-2,2,130})
            assertEquals(x,field.get(x,2,()->{calls.incrementAndGet();return x;}));
        assertEquals(4,calls.get());
        assertEquals(1.5,field.get(1.5,2,()->1.5));
    }

    @Test void placementSharesFrozenTerrainAndLevelQueriesAcrossPlanningStages() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":64},"spawn":{"biome":"test:a"},"biomes":{"filler":["test:a"]}}
          """);
        var samples=new java.util.concurrent.atomic.AtomicInteger();
        var accepts=new java.util.concurrent.atomic.AtomicInteger();
        var penalties=new java.util.concurrent.atomic.AtomicInteger();
        var index=new PlacementIndex(config,(x,z)->{samples.incrementAndGet();return FLAT.sample(x,z);},
                new JointPlanner.LevelConstraint() {
                    public boolean accepts(int level,int x,int z){accepts.incrementAndGet();return true;}
                    public double penalty(int level,int x,int z){penalties.incrementAndGet();return 2.5;}
                },(id,x,z)->true);
        int initial=samples.get();
        var point=new PlacementIndex.Point(4,4);
        for(int repeat=0;repeat<3;repeat++) {
            assertSame(index.sample(4,4),index.sampleAt(6,6));
            assertTrue(index.accepts(2,point));
            assertEquals(2.5,index.penalty(2,point));
        }
        assertEquals(initial+1,samples.get());
        assertEquals(1,accepts.get());assertEquals(1,penalties.get());
    }

    @Test void preflightAcceptsNonSnowyVeryColdAndMixedTemperatureConfiguration() {
        var config=new AdventureWorldConfigParser().parse("""
          {"world":{"radius":128},"spawn":{"biome":"minecraft:plains"},"biomes":{
           "filler":["minecraft:plains"],"terrain_rules":{
             "minecraft:plains":{"temperatures":{"very_cold":2,"cold":1}}}}}
          """);
        var registries=new ContentPreflight.RegistryLookup() {
            public boolean biomeExists(ContentId id){return true;}
            public boolean structureExists(ContentId id){return true;}
            public Boolean snowyAtSeaLevel(ContentId id){return false;}
        };
        assertDoesNotThrow(()->new ContentPreflight().validate(config,registries,
                AdapterRegistry.builder(new GenericBiomeAdapter()).build()));
        var climate=new ClimatePlan(9,config,FLAT);
        assertEquals(new OrganicTemperatureField(9).temperature(2,2,80),
                climate.valueAt(2,2,FLAT.sample(2,2)),"spawn preference must not rewrite the fixed climate");
        assertEquals(climate.prefersType(new ContentId("minecraft:plains"),2,2,FLAT.sample(2,2)),
                climate.allowsEnvironment(new ContentId("minecraft:plains"),2,2,FLAT.sample(2,2)));
    }
}
