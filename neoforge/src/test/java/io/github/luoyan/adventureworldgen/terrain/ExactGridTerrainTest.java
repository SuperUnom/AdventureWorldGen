package io.github.luoyan.adventureworldgen.terrain;

import io.github.luoyan.adventureworldgen.api.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactGridTerrainTest {
    @Test void onlyCachesExactHalfGridAndEvictionPreservesContinuousSamples() {
        var queries=new AtomicInteger();
        MacroTerrain source=(x,z)-> {
            queries.incrementAndGet();
            return new MacroSample(100+x*.02+z*.03,Double.NaN,WaterKind.NONE,false,
                    Long.toString(Double.doubleToRawLongBits(x)),"plains","test");
        };
        var terrain=new ExactGridTerrain(source,8);
        var expected=terrain.sample(-2.5,3);
        assertEquals(expected,terrain.sample(-2.5,3));assertEquals(1,queries.get());
        for(int i=0;i<100;i++)assertEquals(source.sample(i+.5,-i),terrain.sample(i+.5,-i));
        assertEquals(expected,terrain.sample(-2.5,3));
        for(double x:new double[]{.1,Math.nextUp(.5),-.1,-0.0,Double.MAX_VALUE})
            assertEquals(source.sample(x,2),terrain.sample(x,2));
        int before=queries.get();terrain.sample(.1,2);terrain.sample(.1,2);assertEquals(before+2,queries.get());
    }
}
