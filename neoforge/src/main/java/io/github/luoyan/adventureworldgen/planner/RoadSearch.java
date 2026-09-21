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
    RoadSearch(RoadSettings settings, RoadConstruction.Sampler terrain,List<RoadPlan.Reservation> reservations,double radius) {
        this.settings=settings;this.terrain=terrain;this.reservations=reservations;this.radius=radius;
    }
    record Found(List<Vec2> points,double cost,String failure) { boolean valid(){return failure==null;} }
    Found find(Vec2 from,Vec2 to,boolean allowDirect) {
        if(!dry(from)||!dry(to))return new Found(List.of(),0,"INVALID_ENDPOINT");
        double direct=edge(from,to,false);
        if(allowDirect&&Double.isFinite(direct)) {
            var path=new ArrayList<Vec2>();path.add(from);int steps=Math.max(1,(int)Math.ceil(RoadShape.distance(from,to)/8));
            for(int i=1;i<=steps;i++){double t=i/(double)steps;path.add(new Vec2(from.x()+(to.x()-from.x())*t,from.z()+(to.z()-from.z())*t));}
            return new Found(List.copyOf(path),direct,null);
        }
        for(int spacing:new int[]{8,4})for(int pad:new int[]{64,256}) {
            Found result=search(from,to,spacing,pad);
            if(result.valid())return result;
        }
        return new Found(List.of(),0,"SEARCH_DOMAIN_OR_EDGE_BUDGET");
    }
    boolean dryLegal(Vec2 a,Vec2 b){return Double.isFinite(edge(a,b,true));}
    private boolean dry(Vec2 p){var s=terrain.sample(p.x(),p.z());return !s.wet()&&!s.hazardous()&&inside(p.x(),p.z());}
    private boolean inside(double x,double z) {
        if(Math.abs(x)>radius||Math.abs(z)>radius)return false;
        for(var r:reservations)if(Math.abs(x-r.x())<=r.radius()+settings.width()/2.+1
                &&Math.abs(z-r.z())<=r.radius()+settings.width()/2.+1)return false;
        return true;
    }
    double edge(Vec2 a,Vec2 b,boolean dryOnly) {
        double length=RoadShape.distance(a,b);if(length<1e-8)return 0;
        int steps=(int)Math.ceil(length/2);double ox=-(b.z()-a.z())/length*(settings.width()/2.+1),oz=(b.x()-a.x())/length*(settings.width()/2.+1);
        double previous=Double.NaN,first=terrain.sample(a.x(),a.z()).groundSurface(),last=terrain.sample(b.x(),b.z()).groundSurface();
        if(Math.abs(first-last)>length*settings.maximumGrade()+1)return Double.POSITIVE_INFINITY;
        double wetRun=0,maxWet=0,cost=length;
        for(int i=0;i<=steps;i++) {
            double t=i/(double)steps,x=a.x()+(b.x()-a.x())*t,z=a.z()+(b.z()-a.z())*t;
            if(!inside(x,z))return Double.POSITIVE_INFINITY;
            boolean wet=false;double h=0;
            for(int side=-1;side<=1;side++) {
                MacroSample s=terrain.sample(x+ox*side,z+oz*side);
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
    private Found search(Vec2 from,Vec2 to,int spacing,int pad) {
        int minX=(int)Math.floor((Math.min(from.x(),to.x())-from.x()-pad)/spacing),maxX=(int)Math.ceil((Math.max(from.x(),to.x())-from.x()+pad)/spacing);
        int minZ=(int)Math.floor((Math.min(from.z(),to.z())-from.z()-pad)/spacing),maxZ=(int)Math.ceil((Math.max(from.z(),to.z())-from.z()+pad)/spacing);
        var queue=new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::estimate).thenComparingDouble(Visit::cost).thenComparingLong(Visit::order));
        var best=new HashMap<State,Double>();var parent=new HashMap<State,State>();
        var start=new State(0,0,8);best.put(start,0.);long sequence=0;
        queue.add(new Visit(start,0,RoadShape.distance(from,to),sequence++));int expanded=0;
        while(!queue.isEmpty()&&expanded++<30_000) {
            Visit current=queue.remove();State s=current.state();
            if(current.cost()!=best.get(s))continue;
            Vec2 p=point(from,s,spacing);
            if(RoadShape.distance(p,to)<=spacing*1.5&&Double.isFinite(edge(p,to,false))) {
                var path=new ArrayList<Vec2>();path.add(to);
                for(State cursor=s;cursor!=null;cursor=parent.get(cursor))path.add(point(from,cursor,spacing));
                Collections.reverse(path);
                if(path.size()>1&&RoadShape.distance(path.get(path.size()-2),path.getLast())<1e-8)path.removeLast();
                return new Found(List.copyOf(path),current.cost()+RoadShape.distance(p,to),null);
            }
            for(int direction=0;direction<8;direction++) {
                if(s.heading()!=8 && Math.min(Math.abs(s.heading()-direction),8-Math.abs(s.heading()-direction))>2)continue;
                int advance=1;State next;Vec2 q;
                do {
                    next=new State(s.x()+DX[direction]*advance,s.z()+DZ[direction]*advance,direction);q=point(from,next,spacing);
                    if(dry(q))break;
                    var sample=terrain.sample(q.x(),q.z());
                    if(sample.waterKind()!=WaterKind.RIVER){advance=-1;break;}
                    advance++;
                }while((advance-1)*spacing<=settings.maximumBridgeLength());
                if(advance<0||next.x()<minX||next.x()>maxX||next.z()<minZ||next.z()>maxZ||!dry(q))continue;
                double movement=edge(p,q,false);if(!Double.isFinite(movement))continue;
                int turn=s.heading()==8?0:Math.min(Math.abs(direction-s.heading()),8-Math.abs(direction-s.heading()));
                double score=current.cost()+movement+turn*turn*spacing*.6;
                if(score>=best.getOrDefault(next,Double.POSITIVE_INFINITY))continue;
                if(best.size()>=100_000)return new Found(List.of(),0,"SEARCH_STATE_BUDGET");
                best.put(next,score);parent.put(next,s);
                queue.add(new Visit(next,score,score+RoadShape.distance(q,to),sequence++));
            }
        }
        return new Found(List.of(),0,"SEARCH_DOMAIN_OR_EDGE_BUDGET");
    }
    private static Vec2 point(Vec2 start,State s,int spacing){return new Vec2(start.x()+s.x()*spacing,start.z()+s.z()*spacing);}
}
