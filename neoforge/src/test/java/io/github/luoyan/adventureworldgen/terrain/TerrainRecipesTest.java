package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.planner.*;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;

class TerrainRecipesTest {
    @Test void strongPlateauDetailIsIncludedInCapacityAndWorldHeightEnvelopes() {
        var config=new AdventureWorldConfigParser().parse("""
            {"world":{"radius":1000,"terrain":{"composite":false,"mountain_ranges":false,
             "templates":{"plateau":{"vertical_amplitude":220,"detail_strength":2}}}},
             "spawn":{"biome":"example:any"},"biomes":{"filler":["example:any","example:unrestricted"],
             "terrain_rules":{"example:any":{"allowed_templates":["plateau"],"max_height":112}}}}
            """);
        var settings=config.world().terrain();
        assertEquals(1.1,settings.maximumShape(TerrainTemplate.PLATEAU,null));
        assertEquals(1.1,settings.maximumShape(TerrainTemplate.HILLS_1,TerrainTemplate.PLATEAU));
        assertEquals(1.05,TerrainSettings.defaults().maximumShape(TerrainTemplate.PLATEAU,null));
        var coast=new Coastline(List.of(new Vec2(-1000,-1000),new Vec2(1000,-1000),new Vec2(1000,1000),new Vec2(-1000,1000)));
        var capacity=TerrainCapacityPlan.reserve(7331,config,coast,64);
        assertFalse(capacity.reservations().isEmpty());
        for(var r:capacity.reservations()) {
            double high=64+r.baseElevation()+1.1*r.amplitude();
            double fade=TerrainRecipes.smooth(TerrainRecipes.clamp((high-64)/16));
            assertTrue(high+8*fade<=112+1e-9,"strong detail exceeded the reserved upper height");
        }
        var templates=new EnumMap<TerrainTemplate,TerrainTemplate.Settings>(TerrainTemplate.class);
        for(var t:TerrainTemplate.values())templates.put(t,new TerrainTemplate.Settings(t==TerrainTemplate.PLATEAU?1:0,1,220,2));
        var terrain=new RegionTerrain(7331,PlannerProfile.V2,TerrainCapacityPlan.empty(),new TerrainSettings(templates,false,false),null);
        var r=terrain.region(2,2);
        assertTrue(64+r.baseElevation()+1.1*r.amplitude()<=310+1e-9);
    }

    @Test void onlyUpstreamPlateauRecipeAddsAnIndependentSurfaceLayer() {
        var recipes=new TerrainRecipes(7331);
        for(var template:TerrainTemplate.values())for(int x=-80;x<80;x+=7) {
            double detail=recipes.detail(template,x,x*.43,1);
            if(template==TerrainTemplate.PLATEAU)assertTrue(detail>=0&&detail<=.05);
            else assertEquals(0,detail,"non-upstream surface overlay in "+template);
        }
        var defaults=TerrainSettings.defaults();
        var reduced=new EnumMap<TerrainTemplate,TerrainTemplate.Settings>(TerrainTemplate.class);
        for(var t:TerrainTemplate.values()) {
            var s=defaults.get(t);reduced.put(t,new TerrainTemplate.Settings(s.weight(),s.horizontalScale(),s.verticalAmplitude(),0));
        }
        var noDetail=new TerrainRecipes(7331,new TerrainSettings(reduced,true,true));
        for(var t:TerrainTemplate.values()) {
            double difference=0;
            for(int x=-300;x<300;x+=23)difference+=Math.abs(recipes.shape(t,x,93.5,1)-noDetail.shape(t,x,93.5,1));
            assertTrue(difference>1e-6,"detail_strength no longer controls "+t);
        }
    }

    @Test void twelveDistinctBoundedContinuousRecipesAcrossFixedSeeds() {
        assertEquals(12,TerrainTemplate.values().length);
        for(long seed:new long[]{9,7331,8844}) {
            var recipes=new TerrainRecipes(seed);var replay=new TerrainRecipes(seed);
            var fingerprints=new HashSet<Double>();
            for(var t:TerrainTemplate.values()) {
                double min=10,max=-10,fingerprint=0,maxStep=0;
                for(int x=-900;x<=900;x+=31)for(int z=-900;z<=900;z+=37) {
                    double h=recipes.shape(t,x,z,1);min=Math.min(min,h);max=Math.max(max,h);fingerprint+=h;
                    assertTrue(h>=-1e-9&&h<=1.00000001,t+" unbounded "+h);
                    maxStep=Math.max(maxStep,Math.abs(h-recipes.shape(t,x+.001,z,1)));
                }
                assertTrue(max-min>.05,t+" flat recipe");
                assertTrue(maxStep<.005,t+" discontinuous "+maxStep);
                assertTrue(fingerprints.add(fingerprint),"duplicate recipe "+t);
                assertEquals(recipes.shape(t,-100.5,217.2,.7),replay.shape(t,-100.5,217.2,.7));
                assertEquals(recipes.shape(t,20,40,2),recipes.shape(t,10,20,1));
            }
        }
    }
    @Test void intersectionChecksBothCompositeIngredientsAndKeepsCategories() {
        var config=new AdventureWorldConfigParser().parse("""
            {"world":{"radius":1000},"spawn":{"biome":"example:any"},"biomes":{"filler":["example:any"],
             "terrain_rules":{"example:desert":{"allowed_terrain":["plains","hills"],"allowed_templates":["steppe","hills_1","plateau"]}}}}
            """);
        var rule=config.biomes().terrainRules().get(new ContentId("example:desert"));
        assertEquals(Set.of("steppe","hills_1"),rule.effectiveTemplates());
        var legal=new MacroSample(90,Double.NaN,WaterKind.NONE,false,"region","hills","r21","hills_1","steppe",.2,0,0,0,0);
        assertTrue(rule.accepts(legal));
        assertFalse(rule.accepts(new MacroSample(90,Double.NaN,WaterKind.NONE,false,"region","hills","r21","hills_1","plateau",.2,0,0,0,0)));
        assertEquals(config,new AdventureWorldConfigParser().parse(CanonicalConfigJson.write(config)));
    }
    @Test void mountainClassificationUsesFinalSlopeAndRelativeRelief() {
        MacroSample base=new MacroSample(170,Double.NaN,WaterKind.NONE,false,"r","mountains","r21");
        assertEquals("foothill",base.withMorphology(.1,10,0).landform());
        assertEquals("slope",base.withMorphology(.4,40,-8).landform());
        assertEquals("peak",base.withMorphology(.3,50,16).landform());
        var tilted=new TerrainMorphology((x,z)->new MacroSample(170+x*.4,Double.NaN,WaterKind.NONE,false,"r","mountains","r21"));
        var sample=tilted.sample(0,0);
        assertEquals(.4,sample.slope(),1e-10);assertEquals(51.2,sample.localRelief(),1e-9);
        assertEquals("slope",sample.landform());
    }
    @Test void fittedLowlandsRetainVariationWithoutClipping() {
        var reserve=new TerrainCapacityPlan.Reservation(0,0,RegionTerrain.Template.PLAINS,66.0,75.0,32768);
        var plan=new TerrainCapacityPlan(List.of(reserve));
        var region=new RegionTerrain(7331,PlannerProfile.V2,plan);
        int count=0;double min=100,max=-100;
        for(int x=-450;x<450;x+=8)for(int z=-450;z<450;z+=8) {
            if(!new RegionTerrain.GridKey(0,0).equals(region.interiorRegionAt(x,z,232)))continue;
            double h=region.sample(x,z).relativeHeight()+64;min=Math.min(min,h);max=Math.max(max,h);count++;
            assertTrue(h>=66&&h<=75);
        }
        assertTrue(count>20);assertTrue(max-min>.1,"narrow reservation became a flat cap");
    }
    @Test void adaptiveTransitionsDoNotJumpWhenNearestRegionChanges() {
        var regions=new RegionTerrain(7331,PlannerProfile.V2);int boundaries=0;
        for(int z=-1800;z<=1800;z+=91)for(int x=-1800;x<1800;x+=83) {
            var left=regions.regionKeyAt(x,z);var right=regions.regionKeyAt(x+83,z);
            if(left.equals(right))continue;
            double lo=x,hi=x+83;
            for(int i=0;i<40;i++){double mid=(lo+hi)/2;if(regions.regionKeyAt(mid,z).equals(left))lo=mid;else hi=mid;}
            assertEquals(regions.sample(lo-1e-6,z).relativeHeight(),regions.sample(hi+1e-6,z).relativeHeight(),.001,
                    "adaptive width jumped at a region boundary");boundaries++;
        }
        assertTrue(boundaries>100);
    }

    @Test void mountainRangeSpinesAreContinuousAndIndependentOfRegionBorders() {
        var ranges=MountainRangePlan.create(7331,3000,TerrainSettings.defaults());
        assertEquals(ranges,MountainRangePlan.create(7331,3000,TerrainSettings.defaults()));
        assertFalse(ranges.ranges().isEmpty());
        for(var r:ranges.ranges())for(int i=1;i<r.spine().size();i++) {
            var a=r.spine().get(i-1);var b=r.spine().get(i);
            for(int j=0;j<=100;j++)assertEquals(1,ranges.influence(a.x()+(b.x()-a.x())*j/100,a.z()+(b.z()-a.z())*j/100),1e-9);
        }
    }
}
