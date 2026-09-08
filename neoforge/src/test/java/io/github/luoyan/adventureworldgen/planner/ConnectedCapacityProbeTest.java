package io.github.luoyan.adventureworldgen.planner;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectedCapacityProbeTest {
    @Test void thousandsOfAnchorsInTheSameInsufficientComponentAreExploredOnce() {
        var queries=new AtomicInteger();
        var probe=new ConnectedCapacityProbe(cell->{
            queries.incrementAndGet();int x=CellMask.x(cell),z=CellMask.z(cell);
            return x>=0&&x<256&&z>=0&&z<192;
        });
        // 3072 candidate anchors in one island. Independent floods would repeatedly test
        // millions of edges and exhaust the allocator's shared 12-million-operation budget.
        for(int z=0;z<192;z+=4)for(int x=0;x<256;x+=4) {
            var capacity=probe.measure(CellMask.key(x,z),4096);
            assertTrue(capacity.complete());assertEquals(3072,capacity.cells());
            assertFalse(capacity.supports(4096,4096));
        }
        assertTrue(queries.get()<3500,"revisited an already exhausted connected component");
        int prepared=queries.get();
        assertFalse(probe.measure(CellMask.key(32,32),256).supports(4096,256),
                "relaxation accepted a seed with a proven impossible final minimum");
        assertEquals(prepared,queries.get());
    }

    @Test void failedIslandsDoNotHideASeparateViableComponentAndLargeProbesStopEarly() {
        var queries=new AtomicInteger();
        var probe=new ConnectedCapacityProbe(cell->{
            queries.incrementAndGet();int x=CellMask.x(cell),z=CellMask.z(cell);
            return (x>=-40&&x<0&&z>=-40&&z<0)||(x>=40&&x<800&&z>=40&&z<800);
        });
        assertFalse(probe.measure(CellMask.key(-20,-20),4096).supports(4096,4096));
        var available=probe.measure(CellMask.key(400,400),4096);
        assertTrue(available.supports(4096,4096));
        assertFalse(available.complete());
        assertTrue(queries.get()<5000,"successful probe traversed the entire large region");
    }

    @Test void changedOwnershipUsesANewConnectivitySnapshot() {
        var gate=new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.function.LongPredicate available=cell->{
            int x=CellMask.x(cell),z=CellMask.z(cell);
            return x>=0&&x<80&&z>=0&&z<40&&(x!=36||gate.get());
        };
        assertFalse(new ConnectedCapacityProbe(available).measure(CellMask.key(0,0),150).supports(150,150));
        gate.set(true);
        assertTrue(new ConnectedCapacityProbe(available).measure(CellMask.key(0,0),150).supports(150,150));
    }
}
