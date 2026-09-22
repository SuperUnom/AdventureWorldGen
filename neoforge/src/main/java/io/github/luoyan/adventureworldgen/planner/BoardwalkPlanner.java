package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.RoadSettings;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.*;

/** Terrain-following templates with one-dimensional height intervals and exact support validation. */
final class BoardwalkPlanner {
    static final int SUPPORT_REACH=PlanningPolicy.CURRENT.supportReach(), STEP=PlanningPolicy.CURRENT.boardwalkStep();
    private static final int[][] DIR={{1,0},{0,1},{-1,0},{0,-1}};
    record Result(List<RoadPlan.Point3> points,List<RoadPlan.Column> columns,List<RoadPlan.Support> supports,double cost) {}
    private final RoadSettings settings;
    private final MacroTerrain terrain;
    private final List<RoadPlan.Reservation> reservations;
    private final double radius;
    private final RoadWorkBudget work;
    private long operations;
    private String failure="NO_SUPPORTED_BOARDWALK_IN_TEMPLATE_DOMAIN";
    long operations(){return operations;}
    String failureReason(){return failure;}
    private Result reject(String reason){failure=reason;return null;}
    private final Map<Long,MacroSample> samples=new HashMap<>();
    BoardwalkPlanner(RoadSettings settings,MacroTerrain terrain,List<RoadPlan.Reservation> reservations,double radius) {
        this(settings,terrain,reservations,radius,new RoadWorkBudget(settings.maximumOperations(),RoadWorkControl.AUTOMATIC));
    }
    BoardwalkPlanner(RoadSettings settings,MacroTerrain terrain,List<RoadPlan.Reservation> reservations,double radius,RoadWorkBudget work) {
        this.settings=settings;this.terrain=terrain;this.reservations=reservations;this.radius=radius;this.work=work;
    }
    private MacroSample sample(int x,int z) {
        work.operation();operations++;
        long key=RoadPlan.key(x,z);var value=samples.get(key);
        if(value==null) {value=terrain.sample(x+.5,z+.5);if(samples.size()>=100_000)samples.clear();samples.put(key,value);}
        return value;
    }
    private int ground(int x,int z){return (int)Math.floor(sample(x,z).groundSurface())-1;}
    private boolean forbidden(int x,int z) {
        if(Math.hypot(x,z)>radius)return true;
        for(var r:reservations)if(r.bounds().contains(x,z,0))return true;
        var s=sample(x,z);return s.hazardous()||s.wet();
    }
    private int[] anchor(int x,int y,int z) {
        for(int distance=1;distance<=SUPPORT_REACH;distance++)for(var d:DIR) {
            int ax=x+d[0]*distance,az=z+d[1]*distance;
            if(forbidden(ax,az))continue;
            if(ground(ax,az)>=y)return new int[]{ax,az};
        }
        return null;
    }
    Result find(Vec2 from,Vec2 to) {
        int sx=(int)Math.floor(from.x()),sz=(int)Math.floor(from.z()),tx=(int)Math.floor(to.x()),tz=(int)Math.floor(to.z());
        return find(from,to,ground(sx,sz),ground(tx,tz));
    }
    Result findWithConnectors(RoadAccessCandidates.Access from,RoadAccessCandidates.Access to,int sy,int ty) {
        return findWithConnectors(from,to,sy,ty,false);
    }
    Result findWithConnectors(RoadAccessCandidates.Access from,RoadAccessCandidates.Access to,int sy,int ty,boolean primitivesOnly) {
        for(var sketch:templates(from.approach(),to.approach(),sy,ty)) {
            var path=new ArrayList<Vec2>();path.add(from.endpoint());
            for(var p:sketch)if(!p.equals(path.getLast()))path.add(p);
            if(!to.endpoint().equals(path.getLast()))path.add(to.endpoint());
            if(!work.candidate(RoadShape.length(path)))break;
            var geometry=profile(path,sy,ty);if(geometry==null)continue;
            if(!work.validation())break;
            var built=build(geometry,RoadShape.length(path));if(built!=null)return built;
        }
        return null;
    }
    Result find(Vec2 from,Vec2 to,int sy,int ty) {
        return findWithConnectors(RoadAccessCandidates.Access.point(from),RoadAccessCandidates.Access.point(to),sy,ty);
    }
    /** No lattice in X/Z/Y: all candidates have a fixed, bounded horizontal trace. */
    List<List<Vec2>> templates(Vec2 from,Vec2 to,int sy,int ty) {
        var result=new ArrayList<List<Vec2>>();
        // Trace the actual hillside before considering simple geometric fallbacks.
        for(int direction:new int[]{1,-1}) {var trace=contour(from,to,sy,ty,direction);if(trace!=null)result.add(trace);}
        double climb=Math.abs(ty-sy)/Math.min(settings.maximumGrade(),PlanningPolicy.CURRENT.preferredGrade())+16;
        for(int axis=0;axis<2;axis++)for(int direction:new int[]{1,-1}) {
            double extension=Math.max(0,climb-(axis==0?Math.abs(to.z()-from.z()):Math.abs(to.x()-from.x())));
            double x=axis==0?from.x():to.x()+direction*extension,z=axis==0?to.z()+direction*extension:from.z();
            var a=new Vec2(x,z);var b=new Vec2(axis==0?to.x():x,axis==0?z:to.z());
            result.add(dense(List.of(from,a,b,to)));
        }
        boolean reverse=sy>ty;var low=reverse?to:from;var high=reverse?from:to;
        double r=RoadShape.distance(low,high),angle=Math.atan2(low.z()-high.z(),low.x()-high.x());
        if(r>=8&&r<=PlanningPolicy.CURRENT.windingRadius())for(double turns:new double[]{.5,1})for(int direction:new int[]{1,-1}) {
            double sweep=turns*2*Math.PI;int count=(int)Math.ceil(r*sweep/STEP);
            var path=new ArrayList<Vec2>();
            for(int i=0;i<=count;i++) {double a=angle+direction*sweep*i/count;path.add(new Vec2(high.x()+r*Math.cos(a),high.z()+r*Math.sin(a)));}
            path.set(0,low);path.add(high);if(reverse)Collections.reverse(path);result.add(dense(path));
        }
        result.add(dense(List.of(from,to)));
        return result.stream().filter(p->p.size()>1&&RoadShape.length(p)<=work.policy().routeLength()).toList();
    }
    private List<Vec2> contour(Vec2 from,Vec2 to,int sy,int ty,int direction) {
        var path=new ArrayList<Vec2>();path.add(from);var seen=new HashSet<Long>();var at=from;
        double length=0;
        int steps=Math.min(512,work.policy().routeLength()/STEP);
        for(int i=0;i<steps;i++) {
            work.visit();int x=(int)Math.floor(at.x()),z=(int)Math.floor(at.z());
            double gx=ground(x+8,z)-ground(x-8,z),gz=ground(x,z+8)-ground(x,z-8),norm=Math.hypot(gx,gz);
            if(norm<1)return null;
            double dx=-gz/norm*direction,dz=gx/norm*direction;
            // A small grade-directed component follows a slowly changing contour level.
            double rise=Math.signum(ty-sy)*Math.min(.15,settings.maximumGrade()*16/norm);
            dx+=gx/norm*rise;dz+=gz/norm*rise;double scale=Math.hypot(dx,dz);
            var next=new Vec2(at.x()+dx/scale*STEP,at.z()+dz/scale*STEP);
            if(Math.hypot(next.x(),next.z())>radius)return null;
            long key=RoadPlan.key((int)Math.floor(next.x()/STEP),(int)Math.floor(next.z()/STEP));
            if(!seen.add(key))return null;
            path.add(next);length+=STEP;at=next;
            if(length>=Math.abs(ty-sy)/settings.maximumGrade()+8) {path.add(to);return dense(path);}
        }
        return null;
    }
    private List<Vec2> dense(List<Vec2> path) {
        var result=new ArrayList<Vec2>();result.add(path.getFirst());
        if(RoadShape.length(path)>work.policy().routeLength())return List.of();
        for(int i=1;i<path.size();i++) {
            var a=path.get(i-1);var b=path.get(i);int n=(int)Math.ceil(RoadShape.distance(a,b)/STEP);
            for(int k=1;k<=n;k++){work.visit();double t=k/(double)n;result.add(new Vec2(a.x()+(b.x()-a.x())*t,a.z()+(b.z()-a.z())*t));}
        }
        return List.copyOf(result);
    }
    /** Forward/backward interval propagation, then a bounded linear-time height selection. */
    List<RoadPlan.Point3> profile(List<Vec2> sketch,int sy,int ty) {
        var path=dense(sketch);int n=path.size();if(n<2)return null;
        double[] lower=new double[n],upper=new double[n],distance=new double[n];
        for(int i=0;i<n;i++) {
            work.visit();var p=path.get(i);int x=(int)Math.floor(p.x()),z=(int)Math.floor(p.z());
            if(forbidden(x,z))return null;
            int h=ground(x,z),minimum=h-settings.maximumEarthwork(),maximum=h+settings.maximumEarthwork();
            int half=settings.width()/2;
            for(int dz=-half;dz<=half;dz++)for(int dx=-half;dx<=half;dx++)minimum=Math.max(minimum,ground(x+dx,z+dz)-settings.maximumEarthwork());
            for(int d=1;d<=SUPPORT_REACH;d++)for(var dir:DIR)if(!forbidden(x+d*dir[0],z+d*dir[1]))maximum=Math.max(maximum,ground(x+d*dir[0],z+d*dir[1])+2);
            lower[i]=Math.max(-59,minimum);upper[i]=Math.min(315-settings.clearance(),maximum);
            if(i>0)distance[i]=distance[i-1]+RoadShape.distance(path.get(i-1),p);
        }
        if(sy<lower[0]||sy>upper[0]||ty<lower[n-1]||ty>upper[n-1])return null;
        lower[0]=upper[0]=sy;lower[n-1]=upper[n-1]=ty;
        for(int i=1;i<n;i++) {
            double delta=(distance[i]-distance[i-1])*settings.maximumGrade();
            lower[i]=Math.max(lower[i],lower[i-1]-delta);upper[i]=Math.min(upper[i],upper[i-1]+delta);
            if(lower[i]>upper[i]+1e-8)return null;
        }
        for(int i=n-2;i>=0;i--) {
            double delta=(distance[i+1]-distance[i])*settings.maximumGrade();
            lower[i]=Math.max(lower[i],lower[i+1]-delta);upper[i]=Math.min(upper[i],upper[i+1]+delta);
            if(lower[i]>upper[i]+1e-8)return null;
        }
        // Reach the landing height before entering the high plateau; spreading the climb
        // across the final inward connector would force a sharp rise at the cliff edge.
        double climbEnd=distance[n-1];
        for(int i=1;i<n;i++)if((ty>=sy?lower[i]>=ty-settings.maximumEarthwork():upper[i]<=ty+settings.maximumEarthwork())) {
            climbEnd=Math.max(Math.abs(ty-sy)/settings.maximumGrade(),distance[i]-settings.width()*2);break;
        }
        for(int i=1;i<n-1;i++) {
            var a=path.get(i-1);var b=path.get(i);var c=path.get(i+1);
            double before=RoadShape.distance(a,b),after=RoadShape.distance(b,c);
            double cosine=((b.x()-a.x())*(c.x()-b.x())+(b.z()-a.z())*(c.z()-b.z()))/Math.max(1e-8,before*after);
            if(cosine<.7&&distance[i]-settings.width()*2>=Math.abs(ty-sy)/settings.maximumGrade())
                climbEnd=Math.min(climbEnd,distance[i]-settings.width()*2);
        }
        var result=new ArrayList<RoadPlan.Point3>();double y=sy;
        for(int i=0;i<n;i++) {
            double delta=i==0?0:(distance[i]-distance[i-1])*settings.maximumGrade();
            double lo=Math.max(lower[i],y-delta),hi=Math.min(upper[i],y+delta);
            if(lo>hi+1e-8)return null;hi=Math.max(lo,hi);
            y=Math.clamp(sy+(ty-sy)*Math.min(1,distance[i]/Math.max(1,climbEnd)),lo,hi);
            result.add(new RoadPlan.Point3(path.get(i).x(),y,path.get(i).z()));
        }
        return List.copyOf(result);
    }
    private record Raster(RoadPlan.Column column,double distance) {}
    Result build(List<RoadPlan.Point3> path,double cost) {
        double length=RoadShape.length(path.stream().map(p->new Vec2(p.x(),p.z())).toList());
        if(length>work.policy().routeLength())return null;
        // Bound raster work by corridor length, never the area of a long diagonal's box.
        var dense=new ArrayList<RoadPlan.Point3>();dense.add(path.getFirst());
        for(int i=1;i<path.size();i++) {
            var a=path.get(i-1);var b=path.get(i);int count=Math.max(1,(int)Math.ceil(Math.hypot(b.x()-a.x(),b.z()-a.z())/STEP));
            for(int k=1;k<=count;k++){work.visit();double t=k/(double)count;dense.add(new RoadPlan.Point3(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t,a.z()+(b.z()-a.z())*t));}
        }
        path=List.copyOf(dense);
        var cells=new TreeMap<Long,List<Raster>>();int half=settings.width()/2;
        for(int i=1;i<path.size();i++) {
            var a=path.get(i-1);var b=path.get(i);double dx=b.x()-a.x(),dz=b.z()-a.z(),length2=dx*dx+dz*dz;
            if(length2<1e-12)continue;
            if(Math.abs(b.y()-a.y())>Math.sqrt(length2)*settings.maximumGrade()+1e-8)return null;
            for(int z=(int)Math.floor(Math.min(a.z(),b.z()))-half;z<=(int)Math.floor(Math.max(a.z(),b.z()))+half;z++)
                for(int x=(int)Math.floor(Math.min(a.x(),b.x()))-half;x<=(int)Math.floor(Math.max(a.x(),b.x()))+half;x++) {
                    work.visit();
                    double t=Math.clamp(((x+.5-a.x())*dx+(z+.5-a.z())*dz)/length2,0,1);
                    double distance=Math.hypot(x+.5-a.x()-t*dx,z+.5-a.z()-t*dz);
                    if(distance>half+.01)continue;
                    int y=(int)Math.floor(a.y()+(b.y()-a.y())*t+1e-8);
                    if(y< -59||y+settings.clearance()>315||forbidden(x,z)||ground(x,z)>y+settings.maximumEarthwork())return reject("TERRAIN_OR_CUT at "+x+","+y+","+z);
                    if(ground(x,z)<y-settings.maximumEarthwork()&&anchor(x,y-2,z)==null)return reject("NO_SUPPORT at "+x+","+y+","+z);
                    var column=new RoadPlan.Column(x,z,y,y-1,Math.max(y+settings.clearance(),ground(x,z)),true,false,RoadPlan.Kind.BOARDWALK,-1);
                    var stack=cells.computeIfAbsent(RoadPlan.key(x,z),ignored->new ArrayList<>());
                    int replace=-1;
                    for(int k=0;k<stack.size();k++) {
                        var old=stack.get(k);
                        if(Math.abs(old.column.deckY()-y)<=1){replace=k;break;}
                        if(!(column.bottomY()>old.column.clearTopY()||old.column.bottomY()>column.clearTopY()))return reject("SELF_LAYER_COLLISION at "+x+","+y+","+z+" old="+old.column.deckY());
                    }
                    if(replace<0)stack.add(new Raster(column,distance));
                    else if(distance<stack.get(replace).distance)stack.set(replace,new Raster(column,distance));
                }
        }
        var columns=new ArrayList<RoadPlan.Column>();var supports=new LinkedHashSet<RoadPlan.Support>();
        for(var stack:cells.values())for(var raster:stack) {
            work.visit();
            var c=raster.column;int facing=-1;
            for(int d=0;d<4;d++)for(var n:cells.getOrDefault(RoadPlan.key(c.x()-DIR[d][0],c.z()-DIR[d][1]),List.of()))
                if(n.column.deckY()==c.deckY()-1&&facing<0)facing=d;
            columns.add(new RoadPlan.Column(c.x(),c.z(),c.deckY(),c.bottomY(),c.clearTopY(),true,false,c.kind(),facing));
            if(ground(c.x(),c.z())<c.deckY()-settings.maximumEarthwork()&&Math.floorMod(c.x()+c.z(),4)==0) {
                var anchor=anchor(c.x(),c.deckY()-2,c.z());if(anchor==null)return null;
                var beam=new RoadPlan.Support(Math.min(c.x(),anchor[0]),c.deckY()-2,Math.min(c.z(),anchor[1]),
                        Math.max(c.x(),anchor[0]),c.deckY()-2,Math.max(c.z(),anchor[1]),RoadPlan.SupportKind.BEAM);
                boolean clear=true;
                for(int z=beam.minZ();z<=beam.maxZ();z++)for(int x=beam.minX();x<=beam.maxX();x++) {
                    for(var r:reservations)if(r.bounds().contains(x,z,0))clear=false;
                    for(var lower:cells.getOrDefault(RoadPlan.key(x,z),List.of()))
                        if(beam.minY()>lower.column.deckY()&&beam.minY()<=lower.column.clearTopY())clear=false;
                }
                if(!clear)return reject("BEAM_HEADROOM");supports.add(beam);
            }
        }
        columns.sort(RoadPlan.COLUMN_ORDER);
        // Reject a fold that is graph-connected only by detouring around a two-block lip.
        // Other decks, separated by their full clearance interval, are independent layers.
        for(var c:columns)for(var d:DIR)for(var next:cells.getOrDefault(RoadPlan.key(c.x()+d[0],c.z()+d[1]),List.of())) {
            int rise=Math.abs(c.deckY()-next.column.deckY());
            if(rise>1&&rise<=settings.clearance()+1)return reject("STAIR_LIP at "+c.x()+","+c.deckY()+","+c.z());
        }
        // Railings sit outside the walkable deck; never in the turning or lower-layer clearance.
        for(var c:columns)for(var d:DIR) {
            int x=c.x()+d[0],z=c.z()+d[1];
            if(cells.containsKey(RoadPlan.key(x,z))||forbidden(x,z)||ground(x,z)>=c.deckY()-2)continue;
            supports.add(new RoadPlan.Support(x,c.deckY()+1,z,x,c.deckY()+1,z,RoadPlan.SupportKind.RAIL));
        }
        var ordered=supports.stream().sorted(Comparator.comparingInt(RoadPlan.Support::minX).thenComparingInt(RoadPlan.Support::minZ)
                .thenComparingInt(RoadPlan.Support::minY).thenComparing(s->s.kind().ordinal())).toList();
        if(columns.size()+ordered.size()>settings.maximumColumns())return null;
        return new Result(List.copyOf(path),List.copyOf(columns),ordered,cost);
    }
}
