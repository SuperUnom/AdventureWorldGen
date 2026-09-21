package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.plan.RoadPlan;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadSurfaceTest {
    @Test void shortRidgesAndPitsAreRemovedBeforeGradeProjection() {
        for(int width:new int[]{1,3})for(int height:new int[]{-2,1,3}) {
            var result=RoadConstruction.build(List.of(new Vec2(.5,.5),new Vec2(80.5,.5)),
                    RoadPlannerTest.settings("").config().roads(),
                    (x,z)->RoadPlannerTest.land(x>=40&&x<40+width?64+height:64),List.of(),Map.of(),0,0,63);
            assertTrue(result.valid(),result.failure());
            assertTrue(result.columns().stream().allMatch(c->c.deckY()==63),"short ridge/pit survived: "+width+" / "+height);
        }
    }
    @Test void broadHillRemainsAndEveryColumnRespectsEarthwork() {
        var settings=RoadPlannerTest.settings("").config().roads();
        RoadConstruction.Sampler terrain=(x,z)->RoadPlannerTest.land(64+6*Math.exp(-Math.pow((x-60)/22,2)));
        var result=RoadConstruction.build(List.of(new Vec2(.5,.5),new Vec2(120.5,.5)),settings,terrain,List.of(),Map.of(),0,0,63);
        assertTrue(result.valid(),result.failure());
        assertTrue(result.columns().stream().anyMatch(c->c.deckY()>=67),"smoothing flattened a broad hill");
        for(var c:result.columns())assertTrue(Math.abs(c.deckY()-(Math.floor(terrain.sample(c.x()+.5,c.z()+.5).groundSurface())-1))<=settings.maximumEarthwork());
    }
    @Test void reevaluatingSameCorridorDoesNotErodeRoundedHeights() {
        var path=List.of(new Vec2(.5,.5),new Vec2(120.5,.5));
        var settings=RoadPlannerTest.settings("").config().roads();
        RoadConstruction.Sampler terrain=(x,z)->RoadPlannerTest.land(64+4*Math.sin(x/40));
        var first=RoadConstruction.build(path,settings,terrain,List.of(),Map.of(),0,0,63);
        assertTrue(first.valid(),first.failure());
        var existing=new TreeMap<Long,RoadPlan.Column>();
        first.columns().forEach(c->existing.put(RoadPlan.key(c.x(),c.z()),c));
        var again=RoadConstruction.build(path,settings,terrain,List.of(),existing,0,0,63);
        assertTrue(again.valid(),again.failure());assertEquals(first.columns(),again.columns());
    }
}
