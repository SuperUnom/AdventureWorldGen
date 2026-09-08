package io.github.luoyan.adventureworldgen.planner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrganicGrowthTest {
    @Test void equalDistanceDirectionsDoNotProduceACircle() {
        for(long seed:new long[]{1,7331,345705185492107788L}) {
            var shape=new OrganicGrowth(seed,"forest",0,0,65536,80);
            double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for(int i=0;i<64;i++) {
                double angle=i*2*StrictMath.PI/64;
                double cost=shape.score((int)(144*StrictMath.cos(angle)),(int)(144*StrictMath.sin(angle)),80);
                min=Math.min(min,cost);max=Math.max(max,cost);
            }
            assertTrue(max-min>60,"organic preference reduced to radial distance");
        }
    }
}
