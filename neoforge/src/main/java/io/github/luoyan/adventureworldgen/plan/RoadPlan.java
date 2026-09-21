package io.github.luoyan.adventureworldgen.plan;

import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.*;

/** Frozen road geometry. Columns are the authoritative, quantized construction result. */
public record RoadPlan(List<Node> nodes, List<Route> routes, List<Column> columns,
                       List<Reservation> reservations, List<Skipped> skipped, long operations) {
    public static final RoadPlan EMPTY = new RoadPlan(List.of(), List.of(), List.of(), List.of(), List.of(), 0);
    public RoadPlan {
        nodes = List.copyOf(nodes); routes = List.copyOf(routes); reservations = List.copyOf(reservations);
        skipped = List.copyOf(skipped); columns = List.copyOf(columns);
        if (operations < 0 || columns.size() > 2_000_000 || nodes.size() > 256) throw new IllegalArgumentException("road resource limit");
        var ids = new HashSet<String>();
        for (var node : nodes) if (!ids.add(node.id())) throw new IllegalArgumentException("duplicate road node");
        var routeIds = new HashSet<String>();
        for (var route : routes) if (!ids.contains(route.from()) || !ids.contains(route.to()) || !routeIds.add(route.id()))
            throw new IllegalArgumentException("invalid road route endpoints/id");
        var cells = new HashMap<Long,Column>();
        long previous = Long.MIN_VALUE; boolean first = true;
        for (var column : columns) {
            long key = key(column.x(), column.z());
            if (!first && key <= previous) throw new IllegalArgumentException("road columns must be sorted and unique");
            previous = key; first = false; cells.put(key,column);
        }
        if (!routes.isEmpty()) {
            var spawn=nodes.stream().filter(n->n.id().equals("spawn")).findFirst().orElseThrow(()->new IllegalArgumentException("road root missing"));
            long start=key(spawn.x(),spawn.z());
            if(!cells.containsKey(start))throw new IllegalArgumentException("road root column missing");
            var reached=new HashSet<Long>();var queue=new ArrayDeque<Long>();reached.add(start);queue.add(start);
            int[][] adjacent={{1,0},{-1,0},{0,1},{0,-1}};
            while(!queue.isEmpty()) {
                var c=cells.get(queue.remove());
                for(var d:adjacent){long next=key(c.x()+d[0],c.z()+d[1]);var n=cells.get(next);
                    if(n!=null&&Math.abs(n.deckY()-c.deckY())<=1&&reached.add(next))queue.add(next);}
            }
            if(reached.size()!=cells.size())throw new IllegalArgumentException("disconnected frozen road columns");
            var used=new HashSet<String>();routes.forEach(r->{used.add(r.from());used.add(r.to());});
            for(var n:nodes)if((n.required()||used.contains(n.id()))&&!reached.contains(key(n.x(),n.z())))
                throw new IllegalArgumentException("road endpoint missing: "+n.id());
        } else if(!columns.isEmpty()||nodes.stream().anyMatch(n->n.required()&&!n.id().equals("spawn")))
            throw new IllegalArgumentException("road columns or required destinations without routes");
    }
    public static long key(int x, int z) { return ((long)x << 32) | (z & 0xffffffffL); }
    public record Node(String id, int x, int z, boolean required) {
        public Node { if (id == null || id.isBlank()) throw new IllegalArgumentException("missing road node id"); }
    }
    public record Route(String id, String from, String to, List<Vec2> points, double length) {
        public Route {
            points = List.copyOf(points);
            if (id == null || from == null || to == null || points.size() < 2 || !Double.isFinite(length) || length <= 0)
                throw new IllegalArgumentException("invalid road route");
            for (var p : points) if (!Double.isFinite(p.x()) || !Double.isFinite(p.z())) throw new IllegalArgumentException("nonfinite road point");
        }
    }
    /** Inclusive block Y bounds: support bottom, deck top block, and cleared headroom. */
    public record Column(int x, int z, int deckY, int bottomY, int clearTopY, boolean bridge, boolean shoulder) {
        public Column(int x,int z,int deckY,int bottomY,int clearTopY,boolean bridge) { this(x,z,deckY,bottomY,clearTopY,bridge,false); }
        public Column {
            if (Math.abs((long)x) > 30_000_000 || Math.abs((long)z) > 30_000_000 || bottomY < -63
                    || bottomY > deckY || deckY >= 316 || clearTopY < deckY + 3 || clearTopY > 319)
                throw new IllegalArgumentException("invalid road column");
        }
        public boolean protects(int y) { return y >= bottomY && y <= clearTopY; }
    }
    public record Reservation(String instanceId, int x, int z, int radius) {
        public Reservation { if (instanceId == null || radius < 1 || radius > 128) throw new IllegalArgumentException("invalid road reservation"); }
    }
    public record Skipped(String id, String reason) {
        public Skipped { Objects.requireNonNull(id); Objects.requireNonNull(reason); }
    }
}
