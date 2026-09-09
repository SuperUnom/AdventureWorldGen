package io.github.luoyan.adventureworldgen.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OceanBathymetryTest {
    @Test void shelvesDescendGraduallyAndDeepBasinsHaveRelief() {
        for(long seed:new long[]{1,7331,7993}) {
            var field=new OceanBathymetry(seed);
            for(int z=-1500;z<=1500;z+=150) {
                assertEquals(0,field.depth(0,z,0,256));
                assertTrue(field.depth(8,z,8,256)<1,"near coast drops abruptly");
                double shallow=field.depth(60,z,60,256),shelf=field.depth(200,z,200,256);
                double slope=field.depth(420,z,420,256),deep=field.depth(900,z,900,256);
                assertTrue(shallow>=6 && shallow<=11,"missing shallow shelf");
                assertTrue(shelf>shallow+3 && slope>shelf+3 && deep>slope+3,"missing depth tiers");
                double previous=0;
                for(int x=1;x<=1500;x++) {
                    double d=field.depth(x,z,x,256);
                    assertTrue(Math.abs(d-previous)<1,"seabed has a cliff at "+x+","+z);
                    previous=d;
                }
            }
            double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for(int x=2000;x<=4000;x+=32)for(int z=-1500;z<=1500;z+=32) {
                double d=field.depth(x,z,2000,256);min=Math.min(min,d);max=Math.max(max,d);
                assertTrue(d>40 && d<82,"deep relief leaves its depth envelope");
            }
            assertTrue(max-min>18,"deep ocean is still flat");
        }
    }
}
