package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionTerrainCacheTest {
    @Test void neighborhoodReplacementAndConcurrentQueriesPreserveEverySampleField() throws Exception {
        var terrain = new RegionTerrain(7331, PlannerProfile.V2);
        var points = new ArrayList<Vec2>();
        for (int x=-4;x<=4;x++) for (int z=-4;z<=4;z++) {
            points.add(new Vec2(x*768-.5,z*768+.5));
            points.add(new Vec2(x*768+2,z*768+2));
        }
        var expected = points.stream().map(p->terrain.sample(p.x(),p.z())).toList();
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int worker=0;worker<4;worker++) {
                int order = worker;
                tasks.add(()-> {
                    List<Integer> indices = new ArrayList<>();
                    for (int i=0;i<points.size();i++) indices.add(i);
                    Collections.shuffle(indices,new Random(order));
                    for (int i:indices) {
                        var p=points.get(i);
                        assertEquals(expected.get(i),terrain.sample(p.x(),p.z()));
                    }
                    return null;
                });
            }
            for (var result:workers.invokeAll(tasks)) result.get();
        }
    }
}
