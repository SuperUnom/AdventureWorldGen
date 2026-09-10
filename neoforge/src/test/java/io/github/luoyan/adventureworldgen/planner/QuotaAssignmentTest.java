package io.github.luoyan.adventureworldgen.planner;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.luoyan.adventureworldgen.spatial.CellMask;

class QuotaAssignmentTest {
    @Test void spatialGrowthDoesNotJumpAcrossForbiddenTerrainWhenTheNearSideHasCapacity() {
        List<Long> cells=new ArrayList<>();
        for(int z=-20;z<=20;z+=4)for(int x=-20;x<=20;x+=4)if(x!=4)cells.add(CellMask.key(x,z));
        cells.sort(Comparator.comparingDouble((Long cell)->StrictMath.hypot(CellMask.x(cell),CellMask.z(cell)))
                .thenComparingLong(Long::longValue));
        var result=QuotaAssignment.assignSpatial(List.of(new QuotaAssignment.Demand("forest",48,
                cells.stream().mapToLong(Long::longValue).toArray(),CellMask.key(0,0))),10000);
        assertEquals(48,result.masks().getFirst().size());
        for(long cell:result.masks().getFirst().cells())assertTrue(CellMask.x(cell)<4,"growth jumped through forbidden wall");
    }

    @Test void disconnectedLegalCapacityRemainsAvailableToHardQuotaRepair() {
        long origin=CellMask.key(0,0);
        var result=QuotaAssignment.assignSpatial(List.of(new QuotaAssignment.Demand("limited",3,
                new long[]{origin,CellMask.key(4,0),CellMask.key(20,0),CellMask.key(24,0)},origin)),10000);
        assertEquals(3,result.masks().getFirst().size());
        assertTrue(result.masks().getFirst().contains(0,0));
        assertTrue(result.masks().getFirst().contains(4,0));
    }

    @Test void nearbySeedsGrowTogetherInsteadOfIsolatingTheLaterAnchor() {
        List<Long> cells=new ArrayList<>();
        for(int z=-40;z<=40;z+=4)for(int x=-40;x<=40;x+=4)cells.add(CellMask.key(x,z));
        List<QuotaAssignment.Demand> demands=new ArrayList<>();
        for(int x:new int[]{0,8}) {
            cells.sort(Comparator.comparingDouble((Long cell)->StrictMath.hypot(CellMask.x(cell)-x,CellMask.z(cell)))
                    .thenComparingLong(Long::longValue));
            demands.add(new QuotaAssignment.Demand("patch/"+x,48,cells.stream().mapToLong(Long::longValue).toArray(),CellMask.key(x,0)));
        }
        var result=QuotaAssignment.assign(demands,10000);
        for(int i=0;i<2;i++) {
            var mask=result.masks().get(i);var seen=new HashSet<Long>();var q=new ArrayDeque<Long>();
            long anchor=demands.get(i).pinnedCell();seen.add(anchor);q.add(anchor);
            while(!q.isEmpty()) {
                long cell=q.removeFirst();int x=CellMask.x(cell),z=CellMask.z(cell);
                for(int[] d:List.of(new int[]{4,0},new int[]{-4,0},new int[]{0,4},new int[]{0,-4})) {
                    long next=CellMask.key(x+d[0],z+d[1]);
                    if(mask.contains(x+d[0],z+d[1])&&seen.add(next))q.add(next);
                }
            }
            assertEquals(48,mask.size());
            assertEquals(mask.size(),seen.size(),"a neighbouring quota isolated this anchor");
        }
    }

    @Test void repairsGreedyClaimsThroughAnAlternatingPath() {
        var result=QuotaAssignment.assign(List.of(
                new QuotaAssignment.Demand("a",1,new long[]{1,2},null),
                new QuotaAssignment.Demand("b",1,new long[]{1,3},null),
                new QuotaAssignment.Demand("c",1,new long[]{1,3},null)),1000);
        assertArrayEquals(new long[]{2},result.masks().get(0).cells());
        assertEquals(3,result.masks().stream().flatMapToLong(m -> Arrays.stream(m.cells())).distinct().count());
    }
    @Test void matchesExhaustiveFeasibilityForEveryThreeByThreeGraph() {
        for(int bits=0;bits<512;bits++) {
            List<QuotaAssignment.Demand> demands=new ArrayList<>();
            for(int d=0;d<3;d++) {
                List<Long> edges=new ArrayList<>();
                for(int c=0;c<3;c++)if((bits&(1<<(d*3+c)))!=0)edges.add((long)c);
                demands.add(new QuotaAssignment.Demand("d"+d,1,edges.stream().mapToLong(Long::longValue).toArray(),null));
            }
            boolean possible=false;
            for(int a=0;a<3;a++)for(int b=0;b<3;b++)for(int c=0;c<3;c++)
                if(a!=b&&b!=c&&a!=c&&(bits&(1<<a))!=0&&(bits&(1<<(3+b)))!=0&&(bits&(1<<(6+c)))!=0)possible=true;
            try {
                var result=QuotaAssignment.assign(demands,10000);
                assertTrue(possible,"false feasible graph "+bits);
                assertEquals(3,result.masks().stream().flatMapToLong(m -> Arrays.stream(m.cells())).distinct().count());
            } catch(PlanningFailure failure) {
                assertFalse(possible,"missed matching for graph "+bits);
                assertEquals(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,failure.code());
            }
        }
    }
    @Test void reportsSharedCapacityAndNeverMovesPinnedCells() {
        var failure=assertThrows(PlanningFailure.class,()->QuotaAssignment.assign(List.of(
                new QuotaAssignment.Demand("a",2,new long[]{1,2,3},1L),
                new QuotaAssignment.Demand("b",2,new long[]{1,2,3},null)),1000));
        assertEquals("area-capacity",failure.stage());
        var result=QuotaAssignment.assign(List.of(
                new QuotaAssignment.Demand("a",2,new long[]{1,2,3},1L),
                new QuotaAssignment.Demand("b",2,new long[]{1,2,3,4},null)),1000);
        assertTrue(Arrays.stream(result.masks().getFirst().cells()).anyMatch(c->c==1));
        assertTrue(result.masks().stream().allMatch(m->m.size()==2));
    }
}
