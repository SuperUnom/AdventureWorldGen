package io.github.luoyan.adventureworldgen.runtime;

import io.github.luoyan.adventureworldgen.plan.RoadPlan;
import java.util.*;

/** Rebuilt from frozen columns, independent of query order and cache warmth. */
final class RoadIndex {
    private final Map<Long, List<RoadPlan.Column>> columns;
    private final Map<Long, List<RoadPlan.Column>> chunks;
    private final Map<Long,List<RoadPlan.Support>> supports;
    RoadIndex(RoadPlan plan) {
        var cells = new HashMap<Long, List<RoadPlan.Column>>();
        var buckets = new HashMap<Long, List<RoadPlan.Column>>();
        for (var c : plan.columns()) {
            cells.computeIfAbsent(RoadPlan.key(c.x(),c.z()),ignored->new ArrayList<>()).add(c);
            buckets.computeIfAbsent(RoadPlan.key(c.x() >> 4, c.z() >> 4), ignored -> new ArrayList<>()).add(c);
        }
        cells.replaceAll((key,value)->List.copyOf(value));
        columns = Collections.unmodifiableMap(cells);
        var frozen = new HashMap<Long, List<RoadPlan.Column>>();
        buckets.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        chunks = Collections.unmodifiableMap(frozen);
        var supportChunks=new HashMap<Long,List<RoadPlan.Support>>();
        for(var support:plan.supports())for(int z=support.minZ()>>4;z<=support.maxZ()>>4;z++)for(int x=support.minX()>>4;x<=support.maxX()>>4;x++)
            supportChunks.computeIfAbsent(RoadPlan.key(x,z),ignored->new ArrayList<>()).add(support);
        supportChunks.replaceAll((key,value)->List.copyOf(value));supports=Collections.unmodifiableMap(supportChunks);
    }
    RoadPlan.Column at(int x,int z) {var stack=layers(x,z);return stack.isEmpty()?null:stack.getLast();}
    List<RoadPlan.Column> layers(int x,int z){return columns.getOrDefault(RoadPlan.key(x,z),List.of());}
    List<RoadPlan.Support> supports(int x,int z){return supports.getOrDefault(RoadPlan.key(x,z),List.of());}
    List<RoadPlan.Column> chunk(int x, int z) { return chunks.getOrDefault(RoadPlan.key(x, z), List.of()); }
}
