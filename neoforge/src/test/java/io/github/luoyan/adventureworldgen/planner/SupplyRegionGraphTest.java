package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.spatial.CellMask;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SupplyRegionGraphTest {
    @Test void overlappingBiomesCannotReserveTheSameSupplyTwice() {
        var a=new ContentId("test:a");var b=new ContentId("test:b");var graph=new SupplyRegionGraph();
        var cells=List.of(CellMask.key(-16,0),CellMask.key(0,0),CellMask.key(16,0));
        graph.add(a,cells);graph.add(b,cells);assertEquals(768,graph.available(a,0,0));
        graph.reserve(a,0,0,512);assertEquals(256,graph.available(b,0,0));
        graph.reserve(b,0,0,512);assertEquals(0,graph.available(a,0,0));
        assertEquals(-1,graph.available(a,64,64),"unsampled coarse region must remain unknown");
    }
}
