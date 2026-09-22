package io.github.luoyan.adventureworldgen.plan;

import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.*;

/** Frozen layered geometry. Each deck and its cleared volume are distinct from support components. */
public record RoadPlan(List<Node> nodes,List<Route> routes,List<Column> columns,
                       List<Reservation> reservations,List<Skipped> skipped,long operations,List<Support> supports) {
    public static final RoadPlan EMPTY=new RoadPlan(List.of(),List.of(),List.of(),List.of(),List.of(),0,List.of());
    public static final Comparator<Column> COLUMN_ORDER=Comparator.comparingLong((Column c)->key(c.x,c.z)).thenComparingInt(Column::deckY);
    public RoadPlan(List<Node> nodes,List<Route> routes,List<Column> columns,List<Reservation> reservations,List<Skipped> skipped,long operations) {
        this(nodes,routes,columns,reservations,skipped,operations,List.of());
    }
    public RoadPlan {
        routes=List.copyOf(routes);columns=List.copyOf(columns);reservations=List.copyOf(reservations);
        skipped=List.copyOf(skipped);supports=List.copyOf(supports);
        if(operations<0||columns.size()+supports.size()>2_000_000||nodes.size()>256)throw new IllegalArgumentException("road resource limit");
        var layers=new HashMap<Long,List<Column>>();Column previous=null;
        for(var c:columns) {
            if(previous!=null&&COLUMN_ORDER.compare(previous,c)>=0)throw new IllegalArgumentException("road columns must be sorted and unique");
            var stack=layers.computeIfAbsent(key(c.x,c.z),ignored->new ArrayList<>());
            if(!stack.isEmpty()&&stack.getLast().clearTopY>=c.bottomY)throw new IllegalArgumentException("overlapping road layers");
            stack.add(c);previous=c;
        }
        var normalized=new ArrayList<Node>();var ids=new HashSet<String>();
        for(var n:nodes) {
            if(!ids.add(n.id))throw new IllegalArgumentException("duplicate road node");
            var stack=layers.getOrDefault(key(n.x,n.z),List.of());
            int y=n.y;
            if(y==Integer.MIN_VALUE&&!stack.isEmpty())y=stack.getFirst().deckY;
            normalized.add(new Node(n.id,n.x,n.z,n.required,y,n.kind));
        }
        nodes=List.copyOf(normalized);
        var routeIds=new HashSet<String>();
        for(var route:routes)if(!ids.contains(route.from)||!ids.contains(route.to)||!routeIds.add(route.id))
            throw new IllegalArgumentException("invalid road route endpoints/id");
        for(var support:supports)for(int z=support.minZ;z<=support.maxZ;z++)for(int x=support.minX;x<=support.maxX;x++)
            for(var c:layers.getOrDefault(key(x,z),List.of()))
                if(support.maxY>c.deckY&&support.minY<=c.clearTopY)throw new IllegalArgumentException("support blocks road headroom");
        if(!routes.isEmpty()) {
            var spawn=nodes.stream().filter(n->n.id.equals("spawn")).findFirst().orElseThrow(()->new IllegalArgumentException("road root missing"));
            Column start=layers.getOrDefault(key(spawn.x,spawn.z),List.of()).stream().filter(c->c.deckY==spawn.y).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("road root column missing"));
            var seen=new HashSet<Column>();var queue=new ArrayDeque<Column>();seen.add(start);queue.add(start);
            int[][] dirs={{1,0},{-1,0},{0,1},{0,-1}};
            while(!queue.isEmpty()) {
                var c=queue.remove();
                for(var d:dirs)for(var next:layers.getOrDefault(key(c.x+d[0],c.z+d[1]),List.of()))
                    if(Math.abs(next.deckY-c.deckY)<=1&&seen.add(next))queue.add(next);
            }
            if(seen.size()!=columns.size())throw new IllegalArgumentException("disconnected frozen road columns");
            var used=new HashSet<String>();routes.forEach(r->{used.add(r.from);used.add(r.to);});
            for(var n:nodes)if((n.required||used.contains(n.id))&&layers.getOrDefault(key(n.x,n.z),List.of()).stream()
                    .noneMatch(c->c.deckY==n.y&&seen.contains(c)))throw new IllegalArgumentException("road endpoint missing: "+n.id);
        } else if(!columns.isEmpty()||!supports.isEmpty()||nodes.stream().anyMatch(n->n.required&&!n.id.equals("spawn")))
            throw new IllegalArgumentException("road columns or required destinations without routes");
    }
    public static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    public enum NodeKind { DESTINATION, JUNCTION, PASS }
    public enum Kind { GROUND, BRIDGE, BOARDWALK }
    public enum SupportKind { BEAM, RAIL }
    public record Point3(double x,double y,double z) {
        public Point3 {if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("nonfinite road point");}
    }
    public record Node(String id,int x,int z,boolean required,int y,NodeKind kind) {
        public Node(String id,int x,int z,boolean required){this(id,x,z,required,Integer.MIN_VALUE,NodeKind.DESTINATION);}
        public Node {if(id==null||id.isBlank()||kind==null||y!=Integer.MIN_VALUE&&(y< -63||y>=316))throw new IllegalArgumentException("invalid road node");}
    }
    public record Route(String id,String from,String to,List<Vec2> points,double length,List<Point3> geometry,Kind kind) {
        public Route(String id,String from,String to,List<Vec2> points,double length){this(id,from,to,points,length,List.of(),Kind.GROUND);}
        public Route {
            points=List.copyOf(points);geometry=List.copyOf(geometry);Objects.requireNonNull(kind);
            if(id==null||from==null||to==null||points.size()<2||!Double.isFinite(length)||length<=0)throw new IllegalArgumentException("invalid road route");
            for(var p:points)if(!Double.isFinite(p.x())||!Double.isFinite(p.z()))throw new IllegalArgumentException("nonfinite road point");
            if(!geometry.isEmpty()&&geometry.size()!=points.size())throw new IllegalArgumentException("road geometry mismatch");
        }
    }
    /** stairFacing: -1 flat/legacy automatic, otherwise east,south,west,north = 0..3. */
    public record Column(int x,int z,int deckY,int bottomY,int clearTopY,boolean bridge,boolean shoulder,Kind kind,int stairFacing) {
        public Column(int x,int z,int deckY,int bottomY,int clearTopY,boolean bridge){this(x,z,deckY,bottomY,clearTopY,bridge,false);}
        public Column(int x,int z,int deckY,int bottomY,int clearTopY,boolean bridge,boolean shoulder) {
            this(x,z,deckY,bottomY,clearTopY,bridge,shoulder,bridge?Kind.BRIDGE:Kind.GROUND,-1);
        }
        public Column {
            if(Math.abs((long)x)>30_000_000||Math.abs((long)z)>30_000_000||bottomY< -63||bottomY>deckY
                    ||deckY>=316||clearTopY<deckY+3||clearTopY>319||kind==null||bridge!=(kind!=Kind.GROUND)||stairFacing< -1||stairFacing>3)
                throw new IllegalArgumentException("invalid road column");
        }
        public boolean protects(int y){return y>=bottomY&&y<=clearTopY;}
    }
    public record Support(int minX,int minY,int minZ,int maxX,int maxY,int maxZ,SupportKind kind) {
        public Support {
            if(Math.abs((long)minX)>30_000_000||Math.abs((long)maxX)>30_000_000||Math.abs((long)minZ)>30_000_000||Math.abs((long)maxZ)>30_000_000
                    ||minX>maxX||minY>maxY||minZ>maxZ||minY< -63||maxY>319||(long)maxX-minX>32||(long)maxZ-minZ>32||maxY-minY>8||kind==null)
                throw new IllegalArgumentException("invalid road support");
        }
        public boolean contains(int x,int y,int z){return x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
    }
    public record Reservation(String instanceId,BoundsXZ bounds) {
        public Reservation {if(instanceId==null||instanceId.isBlank()||bounds==null)throw new IllegalArgumentException("invalid road reservation");}
    }
    public record Skipped(String id,String reason){public Skipped{Objects.requireNonNull(id);Objects.requireNonNull(reason);}}
}
