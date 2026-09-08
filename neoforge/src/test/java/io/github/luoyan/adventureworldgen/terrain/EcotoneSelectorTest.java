package io.github.luoyan.adventureworldgen.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EcotoneSelectorTest {
    @Test void preservesInteriorAndExcludesThirdBiomeEvenAtJunctions() {
        for (int n=0;n<=100;n++) {
            double threshold=n/100.0;
            assertEquals(0,EcotoneSelector.select(new double[]{0,49,50},threshold,48));
            assertNotEquals(2,EcotoneSelector.select(new double[]{0,1,2},threshold,48));
            assertEquals(1,EcotoneSelector.select(new double[]{Double.POSITIVE_INFINITY,4},threshold,48));
        }
        assertEquals(-1,EcotoneSelector.select(new double[]{Double.POSITIVE_INFINITY},0.5,48));
    }

    @Test void crossingBisectorDoesNotFlipNoiseOrientation() {
        for(double difference:new double[]{-0.1,0,0.1}) {
            assertEquals(0,EcotoneSelector.select(new double[]{100,100+difference},0.25,48));
            assertEquals(1,EcotoneSelector.select(new double[]{100,100+difference},0.75,48));
        }
    }
}
