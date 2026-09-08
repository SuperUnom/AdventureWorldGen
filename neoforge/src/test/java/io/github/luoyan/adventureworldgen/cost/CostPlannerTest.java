package io.github.luoyan.adventureworldgen.cost;
import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CostPlannerTest {
    @Test void repeatedExactDifficultyQueriesDoNotReintegrateTerrainEdges() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        MacroTerrain terrain=(x,z)->{calls.incrementAndGet();return new MacroSample(80,Double.NaN,WaterKind.NONE,false,"r","plains","test");};
        var coast=new Coastline(List.of(new Vec2(-64,-64),new Vec2(64,-64),new Vec2(64,64),new Vec2(-64,64)));
        var costs=new CostPlanner(PlannerProfile.V2).build(terrain,coast,new Vec2(0,0));
        long expected=costs.refinedCostAt(-5,7);int prepared=calls.get();
        for(int i=0;i<5;i++)assertEquals(expected,costs.refinedCostAt(-5,7));
        assertEquals(prepared,calls.get());
    }
    @Test void cachedSamplingPreservesDirectedCostsAtIntegerAndFractionalCoordinates() {
        var coast=new Coastline(List.of(new Vec2(-64,-64),new Vec2(64,-64),new Vec2(64,64),new Vec2(-64,64)));
        MacroTerrain terrain=(x,z)-> {
            double h=80+StrictMath.sin(x*.05)*3+z*.1;
            return new MacroSample(h,Math.abs(x)<7?h+2:Double.NaN,
                    Math.abs(x)<7?WaterKind.RIVER:WaterKind.NONE,false,"r","hills","test");
        };
        var source=new Vec2(.5,-.5);
        var optimized=new CostPlanner(PlannerProfile.V2).build(terrain,coast,source);
        int extent=(int)StrictMath.ceil(StrictMath.hypot(64,64)/16)+2;
        var bounds=new CostDistanceMap.Bounds(-extent,extent,-extent,extent);
        var calculator=new EdgeCostCalculator(terrain,optimized.boundaries(),8);
        var graph=new CompactGridCostGraph(bounds,0,0,16,calculator);
        var reference=CostDistanceMap.build(bounds,0,0,16,node->true,graph,calculator,source,
                PlannerProfile.V2.maximumCostNodes(),PlannerProfile.V2.maximumWorkingMemoryBytes());
        for(int x=-extent;x<=extent;x++)for(int z=-extent;z<=extent;z++) {
            var node=new AdjacentEdgeCache.Node(x,z);
            assertEquals(reference.nodeCost(node),optimized.distances().nodeCost(node));
        }
        for(double x:new double[]{-63.25,-32,-.5,0,16,31.75,65.5})
            for(double z:new double[]{-48.5,-16,0,16.125,63}) {
                var point=new Vec2(x,z);
                assertEquals(reference.costAt(point),optimized.distances().costAt(point));
            }
    }
    private CostPlanner.Result flat() {
        MacroTerrain terrain=(x,z)->new MacroSample(80,Double.NaN,WaterKind.NONE,false,"r","plains","test");
        var coast=new Coastline(List.of(new Vec2(-1024,-1024),new Vec2(1024,-1024),new Vec2(1024,1024),new Vec2(-1024,1024)));
        return new CostPlanner(PlannerProfile.V2).build(terrain,coast,new Vec2(0,0));
    }
    @Test void allQuadrantsAndBothHalvesOfEveryRefinementTileRemainReachable() {
        var costs=flat();
        for(int x:new int[]{-769,-512,-385,-257,-256,-255,-129,-128,-127,-1,0,1,127,128,129,255,256,257,383,511,769})
            for(int z:new int[]{-769,-385,-257,-129,-1,0,127,128,255,383,769}) {
                long cost=costs.refinedCostAt(x,z);
                assertNotEquals(CostDistanceMap.UNREACHABLE,cost,"false unreachable position "+x+","+z);
                assertTrue(cost>=0);
            }
    }
    @Test void refinementPreservesSpawnAndDoesNotInventAPerimeterDetour() {
        var costs=flat();
        assertEquals(0,costs.refinedCostAt(0,0));
        assertTrue(costs.accepts(0,0,0));
        assertEquals(4_000_000,costs.refinedCostAt(4,0));
        assertEquals(128_000_000,costs.refinedCostAt(-128,0));
        assertEquals(256_000_000,costs.refinedCostAt(256,0));
    }
}
