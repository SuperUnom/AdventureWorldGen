package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.plan.RoadPlan;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.*;

/** Materializes junctions and splits routes against the final, layered construction geometry. */
final class RoadTopology {
    private record Segment(int id,int route,RoadPlan.Point3 a,RoadPlan.Point3 b) {}
    private record Cut(double position,RoadPlan.Node node,RoadPlan.Point3 point) {}
    static RoadPlan freeze(List<RoadPlan.Node> input,List<RoadPlan.Route> routes,List<RoadPlan.Column> columns,
                           List<RoadPlan.Reservation> reservations,List<RoadPlan.Skipped> skipped,long operations,
                           List<RoadPlan.Support> supports,int maximumNodes,RoadWorkBudget work) {
        var layers=new HashMap<Long,List<RoadPlan.Column>>();
        for(var c:columns)layers.computeIfAbsent(RoadPlan.key(c.x(),c.z()),ignored->new ArrayList<>()).add(c);
        var nodes=new ArrayList<RoadPlan.Node>();
        for(var n:input) {
            var stack=layers.getOrDefault(RoadPlan.key(n.x(),n.z()),List.of());
            int y=n.y()==Integer.MIN_VALUE&&!stack.isEmpty()?stack.getFirst().deckY():n.y();
            nodes.add(new RoadPlan.Node(n.id(),n.x(),n.z(),n.required(),y,n.kind()));
        }
        var prepared=new ArrayList<RoadPlan.Route>();
        for(var r:routes) {
            var geometry=r.geometry();
            if(geometry.isEmpty()) {
                var points=new ArrayList<RoadPlan.Point3>();
                for(var p:r.points()) {
                    var c=nearest(layers,p.x(),p.z());
                    if(c==null)throw new IllegalArgumentException("route outside frozen surface");
                    points.add(new RoadPlan.Point3(p.x(),c.deckY(),p.z()));
                }
                geometry=List.copyOf(points);
            }
            prepared.add(new RoadPlan.Route(r.id(),r.from(),r.to(),r.points(),r.length(),geometry,r.kind()));
        }
        // Intersections are local segment queries, not all pairs of entire route polylines.
        var tiles=new TreeMap<Long,List<Segment>>();int segmentId=0;
        for(int r=0;r<prepared.size();r++) {
            var geometry=prepared.get(r).geometry();
            for(int i=1;i<geometry.size();i++) {
                var a=geometry.get(i-1);var b=geometry.get(i);var segment=new Segment(segmentId++,r,a,b);
                int steps=Math.max(1,(int)Math.ceil(Math.hypot(b.x()-a.x(),b.z()-a.z())/32));
                var touched=new HashSet<Long>();
                for(int k=1;k<=steps;k++) {
                    double t0=(k-1)/(double)steps,t1=k/(double)steps;
                    int x0=(int)Math.floor((a.x()+(b.x()-a.x())*t0)/64),z0=(int)Math.floor((a.z()+(b.z()-a.z())*t0)/64);
                    int x1=(int)Math.floor((a.x()+(b.x()-a.x())*t1)/64),z1=(int)Math.floor((a.z()+(b.z()-a.z())*t1)/64);
                    for(int z=Math.min(z0,z1);z<=Math.max(z0,z1);z++)for(int x=Math.min(x0,x1);x<=Math.max(x0,x1);x++) {
                        work.visit();long key=RoadPlan.key(x,z);if(touched.add(key))tiles.computeIfAbsent(key,ignored->new ArrayList<>()).add(segment);
                    }
                }
            }
        }
        var compared=new HashSet<Long>();
        for(var segments:tiles.values())for(int a=0;a<segments.size();a++)for(int b=a+1;b<segments.size();b++) {
            work.visit();var one=segments.get(a);var two=segments.get(b);if(one.route==two.route)continue;
            long pair=RoadPlan.key(Math.min(one.id,two.id),Math.max(one.id,two.id));if(!compared.add(pair))continue;
            var p=one.a;var q=one.b;var u=two.a;var v=two.b;
            double dx=q.x()-p.x(),dz=q.z()-p.z(),ex=v.x()-u.x(),ez=v.z()-u.z(),cross=dx*ez-dz*ex;
            if(Math.abs(cross)<1e-8)continue;
            double t=((u.x()-p.x())*ez-(u.z()-p.z())*ex)/cross;
            double w=((u.x()-p.x())*dz-(u.z()-p.z())*dx)/cross;
            if(t<0||t>1||w<0||w>1)continue;
            double x=p.x()+t*dx,z=p.z()+t*dz,y=p.y()+t*(q.y()-p.y());
            if(Math.abs(y-(u.y()+w*(v.y()-u.y())))>1)continue;
            int ix=(int)Math.floor(x),iz=(int)Math.floor(z);
            var c=layers.getOrDefault(RoadPlan.key(ix,iz),List.of()).stream().min(Comparator.comparingDouble(cell->Math.abs(cell.deckY()-y))).orElse(null);
            if(c==null||Math.abs(c.deckY()-y)>1||nodes.stream().anyMatch(n->Math.hypot(n.x()-ix,n.z()-iz)<4&&Math.abs(n.y()-c.deckY())<=1))continue;
            if(nodes.size()>=maximumNodes)throw new RoadWorkBudget.Limit("TOPOLOGY_NODES");
            nodes.add(new RoadPlan.Node("crossing/"+ix+"/"+c.deckY()+"/"+iz,ix,iz,false,c.deckY(),RoadPlan.NodeKind.JUNCTION));
        }
        var split=new ArrayList<RoadPlan.Route>();
        for(var route:prepared) {
            var geometry=route.geometry();var cuts=new ArrayList<Cut>();
            for(var n:nodes) {
                if(n.id().equals(route.from())||n.id().equals(route.to()))continue;
                Cut closest=null;double best=1.01;
                for(int i=1;i<geometry.size();i++) {
                    work.visit();
                    var a=geometry.get(i-1);var b=geometry.get(i);double dx=b.x()-a.x(),dz=b.z()-a.z(),length2=dx*dx+dz*dz;
                    if(length2<1e-12)continue;
                    double t=Math.clamp(((n.x()+.5-a.x())*dx+(n.z()+.5-a.z())*dz)/length2,0,1);
                    double x=a.x()+t*dx,z=a.z()+t*dz,y=a.y()+t*(b.y()-a.y()),distance=Math.hypot(x-n.x()-.5,z-n.z()-.5);
                    if(distance<best&&Math.abs(y-n.y())<=1){best=distance;closest=new Cut(i-1+t,n,new RoadPlan.Point3(x,n.y(),z));}
                }
                if(closest!=null&&closest.position>1e-6&&closest.position<geometry.size()-1-1e-6)cuts.add(closest);
            }
            cuts.sort(Comparator.comparingDouble(Cut::position).thenComparing(c->c.node.id()));
            var path=new ArrayList<RoadPlan.Point3>();path.add(geometry.getFirst());int cursor=1,part=0;String from=route.from();
            for(var cut:cuts) {
                while(cursor<geometry.size()&&cursor<cut.position)path.add(geometry.get(cursor++));
                path.add(cut.point);
                if(add(split,route,part++,from,cut.node.id(),path)){from=cut.node.id();path=new ArrayList<>();path.add(cut.point);}
            }
            while(cursor<geometry.size())path.add(geometry.get(cursor++));
            add(split,route,part,from,route.to(),path);
        }
        var used=new HashSet<String>();split.forEach(r->{used.add(r.from());used.add(r.to());});
        nodes.removeIf(n->!n.required()&&!used.contains(n.id()));
        var facing=new ArrayList<RoadPlan.Column>();int[][] dirs={{1,0},{0,1},{-1,0},{0,-1}};
        for(var c:columns) {
            work.visit();
            int direction=c.stairFacing();
            for(int d=0;d<4&&direction<0;d++)for(var lower:layers.getOrDefault(RoadPlan.key(c.x()-dirs[d][0],c.z()-dirs[d][1]),List.of()))
                if(lower.deckY()==c.deckY()-1){direction=d;break;}
            facing.add(new RoadPlan.Column(c.x(),c.z(),c.deckY(),c.bottomY(),c.clearTopY(),c.bridge(),c.shoulder(),c.kind(),direction));
        }
        return new RoadPlan(nodes,split,facing,reservations,skipped,operations,supports);
    }
    private static boolean add(List<RoadPlan.Route> result,RoadPlan.Route original,int part,String from,String to,List<RoadPlan.Point3> geometry) {
        var points=geometry.stream().map(p->new Vec2(p.x(),p.z())).toList();double length=RoadShape.length(points);
        if(length<1e-6||from.equals(to))return false;
        result.add(new RoadPlan.Route(original.id()+"/"+part,from,to,points,length,geometry,original.kind()));return true;
    }
    private static RoadPlan.Column nearest(Map<Long,List<RoadPlan.Column>> layers,double x,double z) {
        RoadPlan.Column best=null;double distance=Double.POSITIVE_INFINITY;int ix=(int)Math.floor(x),iz=(int)Math.floor(z);
        for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)for(var c:layers.getOrDefault(RoadPlan.key(ix+dx,iz+dz),List.of())) {
            double d=Math.hypot(c.x()+.5-x,c.z()+.5-z);
            if(d<distance){best=c;distance=d;}
        }
        return best;
    }
}
