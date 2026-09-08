package io.github.luoyan.adventureworldgen.cost;
import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.terrain.Coastline;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.planner.PlannerProfile;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CostPlannerTest {
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
