package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.plan.RoadPlan;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LayeredRoadIndexTest {
    @Test void anAscendingLoopKeepsBothDecksAndTheirSeparateClearance() {
        var cells=new ArrayList<RoadPlan.Column>();
        // Cardinal rectangular loop gains 12 blocks before returning over its origin.
        int[][] path={{0,0},{1,0},{2,0},{3,0},{4,0},{4,1},{4,2},{4,3},{4,4},{3,4},{2,4},{1,4},{0,4},{0,3},{0,2},{0,1},{0,0}};
        for(int i=0;i<path.length;i++) {
            int y=64+Math.min(i,12);cells.add(new RoadPlan.Column(path[i][0],path[i][1],y,y-1,y+3,true,false,RoadPlan.Kind.BOARDWALK,-1));
        }
        cells.sort(RoadPlan.COLUMN_ORDER);
        var plan=new RoadPlan(List.of(new RoadPlan.Node("spawn",0,0,true,64,RoadPlan.NodeKind.DESTINATION),
                new RoadPlan.Node("upper",0,0,true,76,RoadPlan.NodeKind.DESTINATION)),
                List.of(new RoadPlan.Route("loop","spawn","upper",List.of(new Vec2(0,0),new Vec2(4,4),new Vec2(0,0)),16)),cells,List.of(),List.of(),0);
        var index=new RoadIndex(plan);
        assertEquals(List.of(64,76),index.layers(0,0).stream().map(RoadPlan.Column::deckY).toList());
        assertEquals(76,index.at(0,0).deckY());assertEquals(2,index.chunk(0,0).stream().filter(c->c.x()==0&&c.z()==0).count());
        var disconnected=new ArrayList<>(cells);disconnected.removeIf(c->c.x()==4&&c.z()==2);
        assertThrows(IllegalArgumentException.class,()->new RoadPlan(plan.nodes(),plan.routes(),disconnected,List.of(),List.of(),0));
    }
}
