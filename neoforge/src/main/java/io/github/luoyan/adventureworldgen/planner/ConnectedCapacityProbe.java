package io.github.luoyan.adventureworldgen.planner;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.LongPredicate;
import io.github.luoyan.adventureworldgen.spatial.CellMask;

/** Connectivity queries for one biome and one unchanged ownership snapshot. */
final class ConnectedCapacityProbe {
    record Capacity(int cells,boolean complete) {
        boolean supports(long minimum,int probeTarget) {
            // A relaxed probe must never accept a component already proven too small.
            return complete?cells>=minimum:cells>=probeTarget;
        }
    }
    private static final int[][] DIR={{4,0},{-4,0},{0,4},{0,-4}};
    private final LongPredicate available;
    private final Long2IntOpenHashMap completed=new Long2IntOpenHashMap();
    private final LongOpenHashSet blocked=new LongOpenHashSet();

    ConnectedCapacityProbe(LongPredicate available) { this.available=available; }

    boolean knownInsufficient(long cell,long minimum) {
        return blocked.contains(cell)||(completed.containsKey(cell)&&completed.get(cell)<minimum);
    }

    Capacity measure(long start,int target) {
        if(target<=0)throw new IllegalArgumentException("capacity target must be positive");
        if(completed.containsKey(start))return new Capacity(completed.get(start),true);
        if(blocked.contains(start))return new Capacity(0,true);
        if(!available.test(start)){blocked.add(start);return new Capacity(0,true);}
        var seen=new LongOpenHashSet();var queue=new LongArrayFIFOQueue();
        seen.add(start);queue.enqueue(start);
        while(!queue.isEmpty()&&seen.size()<target) {
            long cell=queue.dequeueLong();int x=CellMask.x(cell),z=CellMask.z(cell);
            for(int[] d:DIR) {
                long next=CellMask.key(x+d[0],z+d[1]);
                if(seen.contains(next)||blocked.contains(next))continue;
                if(available.test(next)){seen.add(next);queue.enqueue(next);}
                else blocked.add(next);
            }
        }
        boolean complete=queue.isEmpty();int size=seen.size();
        // Every anchor in an exhausted component shares this exact result, including later
        // finer-grid searches and relaxed probes. Large successful probes stop early.
        if(complete)for(long cell:seen)completed.put(cell,size);
        return new Capacity(size,complete);
    }
}
