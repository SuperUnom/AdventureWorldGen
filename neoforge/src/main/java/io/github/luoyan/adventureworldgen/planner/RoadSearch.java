package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.RoadSettings;
import io.github.luoyan.adventureworldgen.plan.RoadPlan;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.*;

/** Bounded heading-aware A*, with straight river-crossing edges between dry banks. */
final class RoadSearch {
    private static final int[] DX={1,1,0,-1,-1,-1,0,1}, DZ={0,1,1,1,0,-1,-1,-1};
    private final RoadSettings settings;
    private final RoadConstruction.Sampler terrain;
    private final List<RoadPlan.Reservation> reservations;
    private final double radius;
    private final RoadWorkBudget work;
    private final java.util.function.LongConsumer replaySamples;
    private record Edge(Vec2 from,Vec2 to,boolean dryOnly) {}
    private record EdgeCost(double cost,long samples) {}
    private final Map<Edge,EdgeCost> edges=new LinkedHashMap<>(1024,.75f,true);
    private long edgeSamples;
    private MacroSample edgeSample(double x,double z){edgeSamples++;return terrain.sample(x,z);}
    RoadSearch(RoadSettings settings, RoadConstruction.Sampler terrain,List<RoadPlan.Reservation> reservations,double radius) {
        this(settings,terrain,reservations,radius,new RoadWorkBudget(settings.maximumOperations(),io.github.luoyan.adventureworldgen.plan.RoadWorkControl.REPORT));
    }
    RoadSearch(RoadSettings settings,RoadConstruction.Sampler terrain,List<RoadPlan.Reservation> reservations,double radius,RoadWorkBudget work) {
        this(settings,terrain,reservations,radius,work,count->{for(long i=0;i<count;i++)work.operation();});
    }
    RoadSearch(RoadSettings settings,RoadConstruction.Sampler terrain,List<RoadPlan.Reservation> reservations,double radius,
               RoadWorkBudget work,java.util.function.LongConsumer replaySamples) {
        this.settings=settings;this.terrain=terrain;this.reservations=reservations;this.radius=radius;this.work=work;
        this.replaySamples=replaySamples;
    }
    record Found(List<Vec2> points,double cost,String failure) { boolean valid(){return failure==null;} }
    Found find(Vec2 from,Vec2 to,boolean allowDirect) {
        if(allowDirect) {var direct=findDirect(from,to);if(direct.valid())return direct;}
        int pad=work.policy().corridorPad();
        return search(from,to,work.policy().coarseStep(),Math.min(from.x(),to.x())-pad,Math.max(from.x(),to.x())+pad,
                Math.min(from.z(),to.z())-pad,Math.max(from.z(),to.z())+pad,false);
    }
    /** Coarse ranking only; exact raster construction remains authoritative. */
    double estimate(List<Vec2> path) {
        double score=0;
        for(int i=1;i<path.size();i++) {
            double cost=coarseEdge(path.get(i-1),path.get(i));
            if(!Double.isFinite(cost))return cost;score+=cost;
        }
        return score;
    }
    List<List<Vec2>> detours(Vec2 from,Vec2 to) {
        double length=RoadShape.distance(from,to);if(length<8)return List.of();
        double nx=-(to.z()-from.z())/length,nz=(to.x()-from.x())/length;
        double offset=Math.min(96,Math.max(32,length*.2));
        var paths=new ArrayList<List<Vec2>>();
        for(int side:new int[]{1,-1}) {
            var a=new Vec2(from.x()+(to.x()-from.x())/3+nx*offset*side,from.z()+(to.z()-from.z())/3+nz*offset*side);
            var b=new Vec2(from.x()+(to.x()-from.x())*2/3+nx*offset*side,from.z()+(to.z()-from.z())*2/3+nz*offset*side);
            paths.add(List.of(from,a,b,to));
        }
        return List.copyOf(paths);
    }
    /** Necessary interval screen on the centre trace. It does not replace full-width validation. */
    boolean groundEnvelope(List<Vec2> path) {
        double lower=-63,upper=315,wetRun=0;
        for(int i=1;i<path.size();i++) {
            var a=path.get(i-1);var b=path.get(i);double length=RoadShape.distance(a,b);
            int count=Math.max(1,(int)Math.ceil(length/4));
            for(int k=0;k<=count;k++) {
                double t=k/(double)count;var s=terrain.sample(a.x()+(b.x()-a.x())*t,a.z()+(b.z()-a.z())*t);
                if(s.hazardous()||s.wet()&&s.waterKind()!=WaterKind.RIVER)return false;
                if(k>0)wetRun=s.wet()?wetRun+length/count:0;
                if(wetRun>settings.maximumBridgeLength())return false;
                double floor=s.wet()?Math.ceil(s.waterSurface())+2:Math.floor(s.groundSurface())-1-settings.maximumEarthwork();
                double ceiling=s.wet()?floor+settings.maximumEarthwork():Math.floor(s.groundSurface())-1+settings.maximumEarthwork();
                double delta=k==0?0:length/count*settings.maximumGrade();
                lower=Math.max(floor,lower-delta);upper=Math.min(ceiling,upper+delta);
                if(lower>upper+1e-8)return false;
            }
        }
        return true;
    }
    private double coarseEdge(Vec2 a,Vec2 b) {
        double length=RoadShape.distance(a,b),cost=length,previous=Double.NaN;
        int steps=Math.max(1,(int)Math.ceil(length/16));
        for(int i=0;i<=steps;i++) {
            double t=i/(double)steps,x=a.x()+(b.x()-a.x())*t,z=a.z()+(b.z()-a.z())*t;
            if(!inside(x,z))return Double.POSITIVE_INFINITY;
            var s=terrain.sample(x,z);
            if(s.hazardous()||s.wet()&&s.waterKind()!=WaterKind.RIVER)return Double.POSITIVE_INFINITY;
            if(s.wet())cost+=32;
            if(!Double.isNaN(previous))cost+=Math.abs(previous-s.travelSurface())*6;
            previous=s.travelSurface();
        }
        return cost;
    }
    Found repair(List<Vec2> path,Vec2 failure) {
        if(failure==null||!work.repair())return new Found(List.of(),0,"LOCAL_REPAIR_BUDGET");
        int nearest=0;double distance=Double.POSITIVE_INFINITY;
        for(int i=0;i<path.size();i++) {double d=RoadShape.distance(path.get(i),failure);if(d<distance){nearest=i;distance=d;}}
        int half=work.policy().repairSide()/2;
        int first=nearest,last=nearest;
        while(first>0&&RoadShape.distance(path.get(first-1),failure)<half-8)first--;
        while(last+1<path.size()&&RoadShape.distance(path.get(last+1),failure)<half-8)last++;
        if(first==last)return new Found(List.of(),0,"NO_LOCAL_REPAIR_ENDPOINTS");
        var part=search(path.get(first),path.get(last),4,failure.x()-half,failure.x()+half,failure.z()-half,failure.z()+half,true);
        if(!part.valid())return part;
        var joined=new ArrayList<>(path.subList(0,first));joined.addAll(part.points());joined.addAll(path.subList(last+1,path.size()));
        return new Found(List.copyOf(joined),RoadShape.length(joined),null);
    }
    Found findDirect(Vec2 from,Vec2 to) {
        if(!dry(from)||!dry(to))return new Found(List.of(),0,"INVALID_ENDPOINT");
        double direct=edge(from,to,false);
        if(!Double.isFinite(direct))return new Found(List.of(),0,"NO_DIRECT_ROUTE");
        var path=new ArrayList<Vec2>();path.add(from);
        int steps=Math.max(1,(int)Math.ceil(RoadShape.distance(from,to)/8));
        for(int i=1;i<=steps;i++){double t=i/(double)steps;path.add(new Vec2(from.x()+(to.x()-from.x())*t,from.z()+(to.z()-from.z())*t));}
        return new Found(List.copyOf(path),direct,null);
    }
    boolean dryLegal(Vec2 a,Vec2 b){return Double.isFinite(edge(a,b,true));}
    private boolean dry(Vec2 p){var s=terrain.sample(p.x(),p.z());return !s.wet()&&!s.hazardous()&&inside(p.x(),p.z());}
    private boolean inside(double x,double z) {
        if(Math.abs(x)>radius||Math.abs(z)>radius)return false;
        for(var r:reservations)if(r.bounds().contains(x,z,settings.width()/2.+1))return false;
        return true;
    }
    double edge(Vec2 a,Vec2 b,boolean dryOnly) {
        var key=new Edge(a,b,dryOnly);var known=edges.get(key);
        if(known!=null){replaySamples.accept(known.samples);return known.cost;}
        edgeSamples=0;double result=computeEdge(a,b,dryOnly);
        edges.put(key,new EdgeCost(result,edgeSamples));
        if(edges.size()>32_768)edges.remove(edges.keySet().iterator().next());
        return result;
    }
    private double computeEdge(Vec2 a,Vec2 b,boolean dryOnly) {
        double length=RoadShape.distance(a,b);if(length<1e-8)return 0;
        int steps=(int)Math.ceil(length/2);double ox=-(b.z()-a.z())/length*(settings.width()/2.+1),oz=(b.x()-a.x())/length*(settings.width()/2.+1);
        double previous=Double.NaN,first=edgeSample(a.x(),a.z()).groundSurface(),last=edgeSample(b.x(),b.z()).groundSurface();
        if(Math.abs(first-last)>length*settings.maximumGrade()+1)return Double.POSITIVE_INFINITY;
        double wetRun=0,maxWet=0,cost=length;
        for(int i=0;i<=steps;i++) {
            double t=i/(double)steps,x=a.x()+(b.x()-a.x())*t,z=a.z()+(b.z()-a.z())*t;
            if(!inside(x,z))return Double.POSITIVE_INFINITY;
            boolean wet=false;double h=0;
            for(int side=-1;side<=1;side++) {
                MacroSample s=edgeSample(x+ox*side,z+oz*side);
                if(s.hazardous()||s.wet()&&(dryOnly||s.waterKind()!=WaterKind.RIVER))return Double.POSITIVE_INFINITY;
                wet|=s.wet();if(side==0)h=s.travelSurface();
                if(!s.wet()&&Math.abs(s.groundSurface()-(first+(last-first)*t))>settings.maximumEarthwork()+2)
                    return Double.POSITIVE_INFINITY;
            }
            if(wet){wetRun+=length/steps;maxWet=Math.max(maxWet,wetRun);cost+=length/steps*5;}else wetRun=0;
            if(!Double.isNaN(previous)&&Math.abs(h-previous)>length/steps*(settings.maximumGrade()+.4)+1)return Double.POSITIVE_INFINITY;
            if(!Double.isNaN(previous))cost+=Math.abs(h-previous)*3;
            previous=h;
        }
        return maxWet>settings.maximumBridgeLength()?Double.POSITIVE_INFINITY:cost;
    }
    private record State(int x,int z,int heading) {}
    private record Visit(State state,double cost,double estimate,long order) {}
    private Found search(Vec2 from,Vec2 to,int spacing,double minX,double maxX,double minZ,double maxZ,boolean local) {
        var queue=new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::estimate).thenComparingDouble(Visit::cost).thenComparingLong(Visit::order));
        var best=new HashMap<State,Double>();var parent=new HashMap<State,State>();
        var start=new State(0,0,8);best.put(start,0.);long sequence=0;
        queue.add(new Visit(start,0,RoadShape.distance(from,to),sequence++));
        int capacity=(local?work.policy().repairExpansions():work.policy().coarseExpansions())*4;
        while(!queue.isEmpty()) {
            if(!work.expansion(local))return new Found(List.of(),0,"RESOURCE_LIMIT_"+(local?"LOCAL":"COARSE")+"_EXPANSIONS");
            var current=queue.remove();var s=current.state();
            if(current.cost()!=best.get(s))continue;
            var p=point(from,s,spacing);
            double terminal=RoadShape.distance(p,to)<=spacing*1.5?(local?edge(p,to,false):coarseEdge(p,to)):Double.POSITIVE_INFINITY;
            if(Double.isFinite(terminal)) {
                var path=new ArrayList<Vec2>();path.add(to);
                for(State cursor=s;cursor!=null;cursor=parent.get(cursor))path.add(point(from,cursor,spacing));
                Collections.reverse(path);
                if(path.size()>1&&RoadShape.distance(path.get(path.size()-2),path.getLast())<1e-8)path.removeLast();
                if(RoadShape.length(path)>work.policy().routeLength())return new Found(List.of(),0,"RESOURCE_LIMIT_ROUTE_LENGTH");
                return new Found(List.copyOf(path),current.cost()+terminal,null);
            }
            for(int direction=0;direction<8;direction++) {
                if(s.heading()!=8&&Math.min(Math.abs(s.heading()-direction),8-Math.abs(s.heading()-direction))>2)continue;
                int advance=1;var next=new State(s.x()+DX[direction],s.z()+DZ[direction],direction);var q=point(from,next,spacing);
                var landing=terrain.sample(q.x(),q.z());
                while(landing.wet()&&landing.waterKind()==WaterKind.RIVER&&advance*spacing<=settings.maximumBridgeLength()+spacing) {
                    advance++;next=new State(s.x()+DX[direction]*advance,s.z()+DZ[direction]*advance,direction);q=point(from,next,spacing);
                    landing=terrain.sample(q.x(),q.z());
                }
                if(landing.wet()||landing.hazardous())continue;
                if(q.x()<minX||q.x()>maxX||q.z()<minZ||q.z()>maxZ)continue;
                double movement=local||advance>1?edge(p,q,false):coarseEdge(p,q);if(!Double.isFinite(movement))continue;
                int turn=s.heading()==8?0:Math.min(Math.abs(direction-s.heading()),8-Math.abs(direction-s.heading()));
                double score=current.cost()+movement+turn*turn*spacing*.6;
                if(score>=best.getOrDefault(next,Double.POSITIVE_INFINITY))continue;
                if(best.size()>=capacity||queue.size()>=capacity)return new Found(List.of(),0,"RESOURCE_LIMIT_SEARCH_STATES");
                best.put(next,score);parent.put(next,s);
                queue.add(new Visit(next,score,score+RoadShape.distance(q,to)*work.policy().heuristicWeight(),sequence++));
            }
        }
        return new Found(List.of(),0,"NO_ROUTE_IN_SEARCH_DOMAIN");
    }
    private static Vec2 point(Vec2 start,State s,int spacing){return new Vec2(start.x()+s.x()*spacing,start.z()+s.z()*spacing);}
}
