package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.*;

/** Mutable planning index; only validated deltas enter the spawn-connected component. */
final class RoadNetwork {
    private static final int TILE=64;
    private static final int[][] DIR={{1,0},{-1,0},{0,1},{0,-1}};
    record Segment(Vec2 a,Vec2 b,double startDistance) {}
    private final Map<Long,List<Segment>> tiles=new HashMap<>();
    private final Map<Long,List<RoadPlan.Column>> layers=new HashMap<>();
    private final Map<Long,List<RoadPlan.Support>> supportTiles=new HashMap<>();
    private final List<RoadPlan.Support> supports=new ArrayList<>();
    final Map<Long,RoadPlan.Column> ground=new HashMap<>();
    private int count;
    int size(){return count+supports.size();}
    List<RoadPlan.Support> supports(){return List.copyOf(supports);}
    List<RoadPlan.Column> columns(){return layers.values().stream().flatMap(List::stream).sorted(RoadPlan.COLUMN_ORDER).toList();}
    RoadPlan.Column at(Vec2 point,int y) {
        var stack=layers.getOrDefault(RoadPlan.key((int)Math.floor(point.x()),(int)Math.floor(point.z())),List.of());
        return stack.stream().min(Comparator.comparingInt(c->y==Integer.MIN_VALUE?c.deckY():Math.abs(c.deckY()-y))).orElse(null);
    }
    List<Segment> nearby(Vec2 point,RoadWorkBudget work) {
        var found=new LinkedHashSet<Segment>();int x=(int)Math.floor(point.x()/TILE),z=(int)Math.floor(point.z()/TILE);
        for(int ring=0;ring<=16;ring++) {
            for(int dz=-ring;dz<=ring;dz++)for(int dx=-ring;dx<=ring;dx++) {
                if(ring>0&&Math.abs(dx)!=ring&&Math.abs(dz)!=ring)continue;
                work.visit();found.addAll(tiles.getOrDefault(RoadPlan.key(x+dx,z+dz),List.of()));
            }
            if(found.size()>=8)break;
        }
        return List.copyOf(found);
    }
    void route(List<Vec2> points,double distance,RoadWorkBudget work) {
        for(int i=1;i<points.size();i++) {
            var a=points.get(i-1);var b=points.get(i);var segment=new Segment(a,b,distance);
            for(int z=(int)Math.floor(Math.min(a.z(),b.z())/TILE);z<=(int)Math.floor(Math.max(a.z(),b.z())/TILE);z++)
                for(int x=(int)Math.floor(Math.min(a.x(),b.x())/TILE);x<=(int)Math.floor(Math.max(a.x(),b.x())/TILE);x++) {
                    tiles.computeIfAbsent(RoadPlan.key(x,z),ignored->new ArrayList<>()).add(segment);
                }
            distance+=RoadShape.distance(a,b);
        }
    }
    record Rejoin(RoadPlan.Column column,List<Vec2> path) {}
    /** Discard a redundant prefix when a new branch touches the built ground network again.
     * Sampling and neighbourhood probes are charged; elevated decks are never projected down.
     */
    Rejoin lastGroundContact(List<Vec2> path,int lastSegment,int reach,RoadWorkBudget work) {
        double[] travelled=new double[path.size()];
        for(int i=1;i<path.size();i++){work.visit();travelled[i]=travelled[i-1]+RoadShape.distance(path.get(i-1),path.get(i));}
        for(int i=Math.min(lastSegment,path.size()-1);i>=1;i--) {
            var a=path.get(i-1);var b=path.get(i);int steps=Math.max(1,(int)Math.ceil(RoadShape.distance(a,b)));
            for(int k=steps;k>=0;k--) {
                double t=k/(double)steps;var p=new Vec2(a.x()+(b.x()-a.x())*t,a.z()+(b.z()-a.z())*t);
                if(travelled[i-1]+(travelled[i]-travelled[i-1])*t<=8)continue;
                RoadPlan.Column best=null;double distance=Double.POSITIVE_INFINITY;
                for(int dz=-reach;dz<=reach;dz++)for(int dx=-reach;dx<=reach;dx++) {
                    work.visit();var c=ground.get(RoadPlan.key((int)Math.floor(p.x())+dx,(int)Math.floor(p.z())+dz));
                    if(c==null||c.shoulder()||c.kind()!=RoadPlan.Kind.GROUND)continue;
                    double d=StrictMath.hypot(c.x()+.5-p.x(),c.z()+.5-p.z());
                    if(d<=reach+.5&&d<distance){best=c;distance=d;}
                }
                if(best==null)continue;
                var joined=new ArrayList<Vec2>();joined.add(new Vec2(best.x()+.5,best.z()+.5));
                for(int j=i;j<path.size();j++)if(RoadShape.distance(joined.getLast(),path.get(j))>1e-7)joined.add(path.get(j));
                if(joined.size()>1)return new Rejoin(best,List.copyOf(joined));
            }
        }
        return null;
    }
    /** An endpoint-to-endpoint shortcut can still close an unhelpful tiny loop midway. */
    boolean hasUnhelpfulExcursion(List<Vec2> path,RoadWorkBudget work) {
        RoadPlan.Column departure=null;double outside=0;
        for(int i=1;i<path.size();i++) {
            var a=path.get(i-1);var b=path.get(i);double length=RoadShape.distance(a,b);
            int steps=Math.max(1,(int)Math.ceil(length));
            for(int k=0;k<=steps;k++) {
                work.visit();double t=k/(double)steps;
                var c=ground.get(RoadPlan.key((int)Math.floor(a.x()+(b.x()-a.x())*t),(int)Math.floor(a.z()+(b.z()-a.z())*t)));
                if(c!=null&&(c.shoulder()||c.kind()!=RoadPlan.Kind.GROUND))c=null;
                if(c==null){if(departure!=null)outside+=length/steps;continue;}
                if(departure!=null&&outside>4) {
                    double old=distance(new Vec2(departure.x()+.5,departure.z()+.5),departure.deckY(),new Vec2(c.x()+.5,c.z()+.5),c.deckY(),work);
                    if(!RoadPlanner.worthwhileLoop(old,outside))return true;
                }
                departure=c;outside=0;
            }
        }
        return false;
    }
    double distance(Vec2 from,int fromY,Vec2 to,int toY,RoadWorkBudget work) {
        var start=at(from,fromY);var end=at(to,toY);if(start==null||end==null)return Double.POSITIVE_INFINITY;
        record Step(RoadPlan.Column column,int distance) {}
        var queue=new ArrayDeque<Step>();var seen=new HashSet<RoadPlan.Column>();queue.add(new Step(start,0));seen.add(start);
        while(!queue.isEmpty()) {
            work.visit();var current=queue.remove();if(current.column.equals(end))return current.distance;
            var c=current.column;
            for(var d:DIR)for(var next:layers.getOrDefault(RoadPlan.key(c.x()+d[0],c.z()+d[1]),List.of()))
                if(Math.abs(c.deckY()-next.deckY())<=1&&seen.add(next))queue.add(new Step(next,current.distance+1));
        }
        return Double.POSITIVE_INFINITY;
    }
    /** Checks delta collisions and connectivity without rebuilding the old network. */
    String validate(List<RoadPlan.Column> delta,List<RoadPlan.Support> beams,Vec2 start,int startY,int maximum,RoadWorkBudget work) {
        var proposed=new HashMap<Long,List<RoadPlan.Column>>();int added=0;
        for(var c:delta) {
            work.visit();long key=RoadPlan.key(c.x(),c.z());boolean replaces=false;
            for(var old:layers.getOrDefault(key,List.of())) {
                if(old.kind()==RoadPlan.Kind.GROUND&&c.kind()==RoadPlan.Kind.GROUND||old.deckY()==c.deckY()){replaces=true;continue;}
                if(old.clearTopY()>=c.bottomY()&&c.clearTopY()>=old.bottomY())return "LAYER_COLLISION";
            }
            for(var support:supportTiles.getOrDefault(key,List.of()))if(support.maxY()>c.deckY()&&support.minY()<=c.clearTopY())return "SUPPORT_HEADROOM";
            for(var other:proposed.getOrDefault(key,List.of()))if(other.clearTopY()>=c.bottomY()&&c.clearTopY()>=other.bottomY())return "LAYER_COLLISION";
            proposed.computeIfAbsent(key,ignored->new ArrayList<>()).add(c);if(!replaces)added++;
        }
        if((long)size()+added+beams.size()>maximum)return "RESOURCE_LIMIT_COLUMNS";
        for(var support:beams)for(int z=support.minZ();z<=support.maxZ();z++)for(int x=support.minX();x<=support.maxX();x++) {
            work.visit();long key=RoadPlan.key(x,z);
            for(var c:layers.getOrDefault(key,List.of()))if(support.maxY()>c.deckY()&&support.minY()<=c.clearTopY())return "SUPPORT_HEADROOM";
            for(var c:proposed.getOrDefault(key,List.of()))if(support.maxY()>c.deckY()&&support.minY()<=c.clearTopY())return "SUPPORT_HEADROOM";
        }
        var seen=new HashSet<RoadPlan.Column>();var queue=new ArrayDeque<RoadPlan.Column>();
        for(var c:delta) {
            boolean connected=c.x()==(int)Math.floor(start.x())&&c.z()==(int)Math.floor(start.z())&&Math.abs(c.deckY()-startY)<=1;
            if(!connected)for(var d:DIR) {
                long key=RoadPlan.key(c.x()+d[0],c.z()+d[1]);
                if(proposed.containsKey(key))continue;
                for(var old:layers.getOrDefault(key,List.of()))if(Math.abs(c.deckY()-old.deckY())<=1)connected=true;
            }
            if(connected&&seen.add(c))queue.add(c);
        }
        while(!queue.isEmpty()) {
            work.visit();var c=queue.remove();
            for(var d:DIR)for(var next:proposed.getOrDefault(RoadPlan.key(c.x()+d[0],c.z()+d[1]),List.of()))
                if(Math.abs(c.deckY()-next.deckY())<=1&&seen.add(next))queue.add(next);
        }
        return seen.size()==delta.size()?null:"DISCONNECTED_DELTA";
    }
    void commit(List<RoadPlan.Column> delta,List<RoadPlan.Support> beams) {
        for(var c:delta) {
            long key=RoadPlan.key(c.x(),c.z());var stack=layers.computeIfAbsent(key,ignored->new ArrayList<>());
            int before=stack.size();stack.removeIf(old->old.kind()==RoadPlan.Kind.GROUND&&c.kind()==RoadPlan.Kind.GROUND||old.deckY()==c.deckY());
            count+=1-(before-stack.size());stack.add(c);stack.sort(Comparator.comparingInt(RoadPlan.Column::deckY));
            if(c.kind()==RoadPlan.Kind.GROUND||c.kind()==RoadPlan.Kind.BRIDGE)ground.put(key,c);
            else {var old=ground.get(key);if(old!=null&&old.deckY()==c.deckY())ground.remove(key);}
        }
        for(var support:beams) {
            supports.add(support);
            for(int z=support.minZ();z<=support.maxZ();z++)for(int x=support.minX();x<=support.maxX();x++)
                supportTiles.computeIfAbsent(RoadPlan.key(x,z),ignored->new ArrayList<>()).add(support);
        }
    }
}
