package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.spatial.CellMask;
import java.util.*;

/** Coarse supply allocation. Reservations are shared across biomes; coarse absence never rejects fine candidates. */
final class SupplyRegionGraph {
    private final Map<ContentId,Map<Long,Integer>> components=new HashMap<>();
    private final Map<ContentId,List<List<Long>>> members=new HashMap<>();
    private final Map<ContentId,int[]> remaining=new HashMap<>();
    private final Set<Long> reserved=new HashSet<>();
    private static final int[][] DIR={{16,0},{-16,0},{0,16},{0,-16}};
    void add(ContentId biome,List<Long> legal) {
        var index=new HashMap<Long,Integer>();var groups=new ArrayList<List<Long>>();var eligible=new HashSet<>(legal);
        for(long seed:legal)if(!index.containsKey(seed)) {
            int id=groups.size();var component=new ArrayList<Long>();var queue=new ArrayDeque<Long>();queue.add(seed);index.put(seed,id);
            while(!queue.isEmpty()) {
                long cell=queue.remove();component.add(cell);
                for(var d:DIR) {
                    long next=CellMask.key(CellMask.x(cell)+d[0],CellMask.z(cell)+d[1]);
                    if(eligible.contains(next)&&!index.containsKey(next)){index.put(next,id);queue.add(next);}
                }
            }
            groups.add(List.copyOf(component));
        }
        components.put(biome,Collections.unmodifiableMap(index));members.put(biome,List.copyOf(groups));remaining.put(biome,groups.stream().mapToInt(List::size).toArray());
    }
    long available(ContentId biome,int x,int z) {
        Integer component=components.getOrDefault(biome,Map.of()).get(CellMask.key(Math.floorDiv(x,16)*16,Math.floorDiv(z,16)*16));
        if(component==null)return -1; // unknown, not an impossibility certificate
        return remaining.get(biome)[component]*256L;
    }
    long total(ContentId biome) {return components.getOrDefault(biome,Map.of()).size()*256L;}
    void reserve(ContentId biome,int x,int z,long area) {
        var index=components.getOrDefault(biome,Map.of());long seed=CellMask.key(Math.floorDiv(x,16)*16,Math.floorDiv(z,16)*16);
        Integer component=index.get(seed);if(component==null)return;
        long count=(area+255)/256;var queue=new ArrayDeque<Long>();var seen=new HashSet<Long>();queue.add(seed);seen.add(seed);
        while(!queue.isEmpty()&&count>0) {
            long cell=queue.remove();if(reserved.add(cell)) {
                count--;
                for(var entry:components.entrySet()){Integer id=entry.getValue().get(cell);if(id!=null)remaining.get(entry.getKey())[id]--;}
            }
            for(var d:DIR){long next=CellMask.key(CellMask.x(cell)+d[0],CellMask.z(cell)+d[1]);
                if(component.equals(index.get(next))&&seen.add(next))queue.add(next);}
        }
    }
}
