package io.github.luoyan.adventureworldgen.spatial;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CellMaskTest {
    @Test void roundTripsNegativeAndDetachedCellsWithoutExposingMutableStorage() {
        long[] input={CellMask.key(-1,-1),CellMask.key(40,8),CellMask.key(0,0)};
        var mask=new CellMask(input); input[0]=CellMask.key(100,100);
        assertTrue(mask.contains(-4,-4)); assertTrue(mask.contains(43,11)); assertFalse(mask.contains(20,4));
        assertEquals(mask,CellMask.decode(mask.encode()));
        mask.cells()[0]=100;
        assertTrue(mask.contains(-1,-1));
        assertThrows(IllegalArgumentException.class,()->new CellMask(new long[]{1,1}));
    }
}
