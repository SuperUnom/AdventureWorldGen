package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.RoadSettings;
import io.github.luoyan.adventureworldgen.plan.RoadWorkControl;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoadSearchCacheTest {
    @Test void repeatedEdgesReuseTerrainButKeepLogicalWork() {
        long[] queries={0},replayed={0};
        var settings=RoadSettings.disabled();
        var work=new RoadWorkBudget(1_000_000,RoadWorkControl.AUTOMATIC);
        var search=new RoadSearch(settings,(x,z)->{queries[0]++;return RoadPlannerTest.land(64);},
                List.of(),1024,work,n->replayed[0]+=n);
        var a=new Vec2(-20.5,-14.5);var b=new Vec2(35.5,18.5);
        double first=search.edge(a,b,false);long sampled=queries[0];
        assertTrue(Double.isFinite(first));assertTrue(sampled>0);
        assertEquals(first,search.edge(a,b,false));
        assertEquals(sampled,queries[0]);assertEquals(sampled,replayed[0]);
    }
}
