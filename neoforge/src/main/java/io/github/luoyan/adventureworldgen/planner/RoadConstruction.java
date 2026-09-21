package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.RoadSettings;
import io.github.luoyan.adventureworldgen.plan.RoadPlan;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.*;

/** Full-width rasterization and a slope-constrained construction surface. No game objects. */
final class RoadConstruction {
    interface Sampler { MacroSample sample(double x, double z); }
    record Result(List<RoadPlan.Column> columns, String failure) {
        boolean valid() { return failure == null; }
    }
    private static final int[][] NEIGHBORS = {{1,0},{-1,0},{0,1},{0,-1}};
    private static final class Cell {
        final int x, z; final MacroSample terrain; final double lower; double upper; final boolean fixed;
        double height, distance;
        Cell(int x, int z, MacroSample terrain, double height, double lower, double upper, double distance, boolean fixed) {
            this.x=x; this.z=z; this.terrain=terrain; this.height=height; this.lower=lower; this.upper=upper; this.distance=distance; this.fixed=fixed;
        }
    }
    static Result build(List<Vec2> path, RoadSettings settings, Sampler sampler,
                        List<RoadPlan.Reservation> reservations, Map<Long,RoadPlan.Column> existing,
                        int spawnX, int spawnZ, int spawnDeck) {
        var dense = new ArrayList<Vec2>(); dense.add(path.getFirst());
        for (int i=1;i<path.size();i++) {
            var a=path.get(i-1); var b=path.get(i); int steps=Math.max(1,(int)Math.ceil(RoadShape.distance(a,b)/4));
            for(int k=1;k<=steps;k++){double t=k/(double)steps;dense.add(new Vec2(a.x()+(b.x()-a.x())*t,a.z()+(b.z()-a.z())*t));}
        }
        path=dense;
        var cells = new TreeMap<Long, Cell>();
        double coreHalf = settings.width() / 2.0;
        double half = coreHalf + 1;
        // Previously accepted routes are still tentative, not published frozen columns. Re-solve
        // their shared elevation envelope with the new edge: pinning already-rounded Y values
        // can make an otherwise feasible junction fail at the earthwork boundary.
        for(var old:existing.values()) {
            var sample=sampler.sample(old.x()+.5,old.z()+.5);
            double ground=StrictMath.floor(sample.groundSurface())-1;
            double lower=sample.wet()?StrictMath.ceil(sample.waterSurface())+2:ground-settings.maximumEarthwork();
            double upper=sample.wet()?lower+settings.maximumEarthwork():ground+settings.maximumEarthwork();
            boolean fixed=Math.abs(old.x()-spawnX)<=2&&Math.abs(old.z()-spawnZ)<=2;
            double desired=Math.clamp(old.deckY(),lower,upper);
            if(fixed){desired=spawnDeck;lower=spawnDeck;upper=spawnDeck;}
            cells.put(RoadPlan.key(old.x(),old.z()),new Cell(old.x(),old.z(),sample,desired,lower,upper,
                    old.shoulder()?half:0,fixed));
        }
        var pathCells=new HashSet<Long>();
        long addedColumns=0;
        for (int i=1;i<path.size();i++) {
            Vec2 a=path.get(i-1), b=path.get(i);
            double dx=b.x()-a.x(), dz=b.z()-a.z(), length2=dx*dx+dz*dz;
            if (length2 < 1e-12) continue;
            double ah=sampler.sample(a.x(),a.z()).groundSurface()-1, bh=sampler.sample(b.x(),b.z()).groundSurface()-1;
            int minX=(int)Math.floor(Math.min(a.x(),b.x())-half), maxX=(int)Math.floor(Math.max(a.x(),b.x())+half);
            int minZ=(int)Math.floor(Math.min(a.z(),b.z())-half), maxZ=(int)Math.floor(Math.max(a.z(),b.z())+half);
            for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++) {
                double t=Math.clamp(((x+.5-a.x())*dx+(z+.5-a.z())*dz)/length2,0,1);
                double distance=StrictMath.hypot(x+.5-a.x()-t*dx,z+.5-a.z()-t*dz);
                if(distance>half)continue;
                long key=RoadPlan.key(x,z); pathCells.add(key); Cell prior=cells.get(key);
                if(prior!=null && prior.distance<=distance)continue;
                for(var r:reservations)if(r.bounds().contains(x+.5,z+.5,0))return fail("STRUCTURE_RESERVATION");
                MacroSample sample=sampler.sample(x+.5,z+.5);
                if(distance>coreHalf && sample.wet()){pathCells.remove(key);continue;}
                if(sample.hazardous() || sample.wet() && sample.waterKind()!=WaterKind.RIVER)return fail("FORBIDDEN_TERRAIN");
                double ground=StrictMath.floor(sample.groundSurface())-1;
                double lower=sample.wet()?StrictMath.ceil(sample.waterSurface())+2:ground-settings.maximumEarthwork();
                double upper=sample.wet()?lower+settings.maximumEarthwork():ground+settings.maximumEarthwork();
                double desired=Math.clamp(ah+(bh-ah)*t,lower,upper);
                boolean fixed=false;
                var old=existing.get(key);
                if(Math.abs(x-spawnX)<=2 && Math.abs(z-spawnZ)<=2) { desired=spawnDeck; lower=desired; upper=desired; fixed=true; }
                if(desired<lower || desired>upper || upper>=316 || lower< -61)return fail("HEIGHT_LIMIT");
                if(prior==null && old==null)addedColumns++;
                cells.put(key,new Cell(x,z,sample,desired,lower,upper,distance,fixed));
                if((long)existing.size()+addedColumns>settings.maximumColumns())return fail("COLUMN_BUDGET");
            }
        }
        // A short rock/ridge must not seed a raised pyramid in the grade solver. Use a
        // median of natural dry ground in a 9x9 window of the constructed corridor.
        // Recompute from terrain (not previously rounded decks), so repeated candidate
        // evaluations cannot progressively erode the accepted network. Bridges stay pinned
        // to their water clearance, and the earthwork bounds still limit cuts and fills.
        int[] neighborhood=new int[81];
        for(var c:cells.values()) {
            if(c.fixed||c.terrain.wet())continue;
            int count=0;
            for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++) {
                var neighbor=cells.get(RoadPlan.key(c.x+dx,c.z+dz));
                if(neighbor!=null&&!neighbor.terrain.wet())
                    neighborhood[count++]=(int)StrictMath.floor(neighbor.terrain.groundSurface())-1;
            }
            Arrays.sort(neighborhood,0,count);
            c.height=Math.clamp(neighborhood[count/2],c.lower,c.upper);
        }
        // First propagate upper bounds downward. This leaves room to lower a ramp near a pinned
        // spawn/junction, instead of incorrectly declaring failure because a preferred height
        // would require lifting the fixed endpoint. A feasible upper envelope bounds the following
        // greatest-first projection of desired heights.
        record Entry(long key,double height) {}
        var upperQueue=new PriorityQueue<Entry>(Comparator.comparingDouble(Entry::height).thenComparingLong(Entry::key));
        cells.forEach((key,c)->upperQueue.add(new Entry(key,c.upper)));
        while(!upperQueue.isEmpty()) {
            var e=upperQueue.remove();var c=cells.get(e.key());if(e.height()!=c.upper)continue;
            for(var d:NEIGHBORS) {
                long key=RoadPlan.key(c.x+d[0],c.z+d[1]);var n=cells.get(key);if(n==null)continue;
                double maximum=c.upper+(c.fixed&&n.fixed?1.0:settings.maximumGrade());
                if(n.upper<=maximum+1e-8)continue;
                if(n.lower>maximum+1e-8)return fail("GRADE_OR_EARTHWORK");
                n.upper=maximum;upperQueue.add(new Entry(key,maximum));
            }
        }
        cells.values().forEach(c->c.height=Math.clamp(c.height,c.lower,c.upper));
        var queue=new PriorityQueue<Entry>(Comparator.comparingDouble(Entry::height).reversed().thenComparingLong(Entry::key));
        cells.forEach((key,c)->queue.add(new Entry(key,c.height)));
        while(!queue.isEmpty()) {
            var e=queue.remove(); var c=cells.get(e.key());
            if(e.height()!=c.height)continue;
            for(var d:NEIGHBORS) {
                long key=RoadPlan.key(c.x+d[0],c.z+d[1]); var n=cells.get(key);
                if(n==null) {
                    var old=existing.get(key);
                    if(old!=null && Math.abs(StrictMath.floor(c.height)-old.deckY())>1)return fail("JUNCTION_HEIGHT");
                    continue;
                }
                double minimum=c.height-(c.fixed && n.fixed ? 1.0 : settings.maximumGrade());
                if(n.height+1e-8>=minimum)continue;
                if(minimum>n.upper+1e-8)return fail("GRADE_OR_EARTHWORK");
                n.height=minimum; queue.add(new Entry(key,minimum));
            }
        }
        var result=new ArrayList<RoadPlan.Column>();
        for(var c:cells.values()) {
            int deck=(int)StrictMath.floor(c.height+1e-8);
            int ground=(int)StrictMath.floor(c.terrain.groundSurface())-1;
            boolean bridge=c.terrain.wet();
            int bottom=bridge?deck-1:Math.min(ground,deck)-1;
            int clear=Math.max(deck+settings.clearance(),bridge?deck:ground);
            if(bottom< -63 || clear>319)return fail("HEIGHT_LIMIT");
            result.add(new RoadPlan.Column(c.x,c.z,deck,bottom,clear,bridge,c.distance>coreHalf));
        }
        // Four-neighbour block connectivity catches diagonal narrow gaps introduced by rasterizing.
        if(!result.isEmpty()) {
            var seen=new HashSet<Long>(); var todo=new ArrayDeque<Long>();
            long first=pathCells.stream().filter(cells::containsKey).min(Long::compare).orElseThrow();
            todo.add(first);seen.add(first);
            while(!todo.isEmpty()) {
                Cell c=cells.get(todo.remove());
                for(var d:NEIGHBORS) {long k=RoadPlan.key(c.x+d[0],c.z+d[1]); if(cells.containsKey(k)&&seen.add(k))todo.add(k);}
            }
            if(!seen.containsAll(pathCells))return fail("DISCONNECTED_RASTER");
        }
        return new Result(List.copyOf(result),null);
    }
    private static Result fail(String reason) { return new Result(List.of(),reason); }
}
