package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.plan.RoadPlan;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadJunctionsTest {
    private static RoadPlan.Route route(List<Vec2> points) {return new RoadPlan.Route("a->b","a","b",points,RoadShape.length(points));}
    @Test void repeatedCrossingsReuseTheExistingCorridorInBothDirections() {
        var existing=List.of(new Vec2(0,0),new Vec2(40,0),new Vec2(100,0));
        var incoming=List.of(new Vec2(0,0),new Vec2(30,10),new Vec2(60,-10),new Vec2(90,10),new Vec2(110,10));
        for(boolean reverse:new boolean[]{false,true}) {
            var path=reverse?incoming.reversed():incoming;
            var merged=RoadJunctions.merge(path,List.of(route(existing)),1.1);
            assertEquals(path.getFirst(),merged.getFirst());assertEquals(path.getLast(),merged.getLast());
            assertTrue(merged.stream().filter(p->p.x()<75).allMatch(p->p.z()==0),"a duplicate lens remains: "+merged);
            assertTrue(RoadShape.length(merged)<RoadShape.length(path));
        }
    }
    @Test void oneCrossingIsStillAnOrdinaryJunctionAndUsefulShortcutSurvives() {
        var straight=List.of(new Vec2(0,0),new Vec2(100,0));
        var crossing=List.of(new Vec2(50,-30),new Vec2(50,30));
        assertEquals(crossing,RoadJunctions.merge(crossing,List.of(route(straight)),1.1));
        var detour=List.of(new Vec2(0,0),new Vec2(0,100),new Vec2(100,100),new Vec2(100,0));
        assertEquals(straight,RoadJunctions.merge(straight,List.of(route(detour)),1.1));
    }
    @Test void collinearSharedSectionsStayStableWithoutDuplicateVertices() {
        var old=List.of(new Vec2(0,0),new Vec2(25,0),new Vec2(50,0),new Vec2(75,0),new Vec2(100,0));
        var path=List.of(new Vec2(0,0),new Vec2(10,0),new Vec2(50,8),new Vec2(90,0),new Vec2(100,0));
        var merged=RoadJunctions.merge(path,List.of(route(old)),1.1);
        assertTrue(merged.stream().allMatch(p->p.z()==0));
        for(int i=1;i<merged.size();i++)assertTrue(RoadShape.distance(merged.get(i-1),merged.get(i))>0);
        assertEquals(merged,RoadJunctions.merge(merged,List.of(route(old)),1.1));
    }
}
