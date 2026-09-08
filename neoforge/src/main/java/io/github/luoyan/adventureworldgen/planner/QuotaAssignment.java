package io.github.luoyan.adventureworldgen.planner;

import java.util.*;

/** Integral bipartite b-matching. Alternating paths repair greedy claims without dropping a quota. */
public final class QuotaAssignment {
    public record Demand(String id, int minimum, long[] candidates, Long pinnedCell) {
        public Demand { candidates = candidates.clone(); }
        @Override public long[] candidates() { return candidates.clone(); }
        public int candidateCount() { return candidates.length; }
    }
    public static final class CapacityFailure extends PlanningFailure {
        private final List<String> demands;
        private final long visits;
        private CapacityFailure(List<String> demands,long required,long available,long unfilled,long visits) {
            super(Code.NO_SOLUTION_IN_DOMAIN,"area-capacity",
                    "shared legal cells cannot satisfy all quotas in this candidate domain",
                    Map.of("demands",demands,"required_cells",required,"available_cells",available,
                            "unfilled_cells",unfilled,"visits",visits));
            this.demands=List.copyOf(demands);this.visits=visits;
        }
        public List<String> demands() { return demands; }
        public long visits() { return visits; }
    }
    public record Result(List<CellMask> masks, long visits) { public Result { masks = List.copyOf(masks); } }

    public static Result assign(List<Demand> demands, long budget) {
        return assign(demands,budget,false);
    }

    /** Spatial preference for world-aligned quart-cell keys. Feasibility repair stays complete. */
    public static Result assignSpatial(List<Demand> demands, long budget) {
        return assign(demands,budget,true);
    }

    private static Result assign(List<Demand> demands, long budget, boolean spatial) {
        Map<Long, Integer> owner = new HashMap<>(), pinned = new HashMap<>();
        int[] count = new int[demands.size()];
        for (int i = 0; i < demands.size(); i++) {
            var d = demands.get(i);
            if (d.minimum < 1) throw new IllegalArgumentException("quota must be positive");
            if (d.pinnedCell == null) continue;
            if (Arrays.stream(d.candidates).noneMatch(c -> c == d.pinnedCell))
                throw new IllegalArgumentException("pinned cell is not a candidate");
            if (pinned.putIfAbsent(d.pinnedCell, i) != null)
                throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, "area-capacity", "conflicting pinned seeds");
            owner.put(d.pinnedCell, i); count[i]++;
        }
        long visits = 0;
        // Scarcity order is deterministic; augmenting paths, rather than this ordering, ensure feasibility.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < demands.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> demands.get(i).candidates.length / (double) demands.get(i).minimum)
                .thenComparing(i -> demands.get(i).id));
        if (spatial) visits = growFrontiers(demands, order, owner, count, budget);
        // Advance every spatially ranked frontier once per round. Filling an entire quota
        // before its neighbours lets the first patch surround their pinned anchors, leaving
        // one-cell islands even on uniformly legal terrain. This changes preference only;
        // the complete alternating-path repair below still resolves shared capacity.
        int[] cursor = new int[demands.size()];
        boolean advanced;
        do {
            advanced = false;
            for (int i : order) {
                var d = demands.get(i);
                if (count[i] >= d.minimum) continue;
                while (cursor[i] < d.candidates.length) {
                    long cell = d.candidates[cursor[i]++];
                    if (++visits > budget) throw exhausted(visits, budget);
                    if (!owner.containsKey(cell)) {
                        owner.put(cell, i); count[i]++; advanced = true; break;
                    }
                }
            }
        } while (advanced);
        for (int start : order) while (count[start] < demands.get(start).minimum) {
            int[] parent = new int[demands.size()];
            Arrays.fill(parent, -2); parent[start] = -1;
            long[] via = new long[demands.size()];
            ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(start);
            int end = -1; long free = 0;
            search: while (!queue.isEmpty()) {
                int current = queue.remove();
                for (long cell : demands.get(current).candidates) {
                    if (++visits > budget) throw exhausted(visits, budget);
                    if (pinned.containsKey(cell)) continue;
                    Integer previous = owner.get(cell);
                    if (previous == null) { end = current; free = cell; break search; }
                    if (parent[previous] == -2) {
                        parent[previous] = current; via[previous] = cell; queue.add(previous);
                    }
                }
            }
            if (end < 0) {
                var competing = new ArrayList<String>();
                Set<Long> capacity = new HashSet<>(); long required = 0;
                for (int i = 0; i < parent.length; i++) if (parent[i] != -2) {
                    competing.add(demands.get(i).id); required += demands.get(i).minimum;
                    for (long c : demands.get(i).candidates) if (!pinned.containsKey(c) || parent[pinned.get(c)] != -2) capacity.add(c);
                }
                throw new CapacityFailure(competing,required,capacity.size(),
                        demands.get(start).minimum-count[start],visits);
            }
            for (int current = end; current >= 0; current = parent[current]) {
                Integer previous = owner.put(free, current);
                if (previous != null) count[previous]--;
                count[current]++;
                free = via[current];
            }
        }
        List<List<Long>> cells = new ArrayList<>();
        for (int i = 0; i < demands.size(); i++) cells.add(new ArrayList<>());
        owner.forEach((cell, i) -> cells.get(i).add(cell));
        return new Result(cells.stream().map(CellMask::new).toList(), visits);
    }

    private static PlanningFailure exhausted(long visits, long budget) {
        return new PlanningFailure(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED, "area-capacity",
                "alternating-path operation budget exhausted", Map.of("visits", visits, "budget", budget));
    }

    private static long growFrontiers(List<Demand> demands, List<Integer> order,
                                      Map<Long,Integer> owner, int[] count, long budget) {
        List<Frontier> frontiers=new ArrayList<>();
        long visits=0;
        for(var d:demands) {
            var frontier=new Frontier(d.candidates);
            frontiers.add(frontier);
            if(d.pinnedCell!=null)frontier.expand(d.pinnedCell);
        }
        boolean advanced;
        do {
            advanced=false;
            for(int i:order) {
                if(count[i]>=demands.get(i).minimum)continue;
                var frontier=frontiers.get(i);
                while(!frontier.queue.isEmpty()) {
                    long cell=frontier.queue.remove();
                    if(++visits>budget)throw exhausted(visits,budget);
                    if(owner.containsKey(cell))continue;
                    owner.put(cell,i);count[i]++;frontier.expand(cell);advanced=true;break;
                }
            }
        } while(advanced);
        return visits;
    }

    private static final class Frontier {
        private static final int[][] DIRECTIONS={{4,0},{-4,0},{0,4},{0,-4}};
        final Map<Long,Integer> ranks=new HashMap<>();
        final Set<Long> queued=new HashSet<>();
        final PriorityQueue<Long> queue;
        Frontier(long[] candidates) {
            for(int i=0;i<candidates.length;i++)ranks.putIfAbsent(candidates[i],i);
            queue=new PriorityQueue<>(Comparator.comparingInt((Long cell)->ranks.get(cell)).thenComparingLong(Long::longValue));
        }
        void expand(long cell) {
            int x=CellMask.x(cell),z=CellMask.z(cell);
            for(int[] d:DIRECTIONS) {
                long nx=(long)x+d[0],nz=(long)z+d[1];
                if(nx<Integer.MIN_VALUE||nx>Integer.MAX_VALUE||nz<Integer.MIN_VALUE||nz>Integer.MAX_VALUE)continue;
                long next=CellMask.key((int)nx,(int)nz);
                if(ranks.containsKey(next)&&queued.add(next))queue.add(next);
            }
        }
    }
}
