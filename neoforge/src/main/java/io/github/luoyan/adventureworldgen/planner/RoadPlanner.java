package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.RoadSettings;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import io.github.luoyan.adventureworldgen.planner.RoadAccessCandidates.Access;
import java.util.*;
import java.util.function.BiFunction;

/** Post-layout road planning. Never moves a structure, alters biome ownership or reads game state. */
public final class RoadPlanner {
    private final long seed;
    private final AdventureWorldConfig config;
    private final RoadSettings settings;
    private final MacroTerrain terrain;
    private final double radius;
    private long operations;
    private long edgeLimit=Long.MAX_VALUE;
    private static final class EdgeBudget extends RuntimeException {
        EdgeBudget() { super("bounded road edge search exhausted",null,false,false); }
    }
    private final List<RoadPlan.Skipped> skipped=new ArrayList<>();
    private final List<RoadPlan.Reservation> reservations=new ArrayList<>();
    private final Map<Long,RoadPlan.Column> columns=new TreeMap<>();
    private final List<RoadPlan.Route> routes=new ArrayList<>();
    private final Map<Long,MacroSample> samples=new HashMap<>();
    private int spawnX,spawnZ,spawnDeck;
    private final Map<String,List<Access>> accessCandidates=new TreeMap<>();
    private final Set<String> lockedAccess=new HashSet<>();
    private static final int MAX_ACCESS_PAIRS=16;
    private static final long PAIR_OPERATIONS=750_000, ACCESS_OPERATIONS=150_000;

    public RoadPlanner(long seed,AdventureWorldConfig config,MacroTerrain terrain) {
        this.seed=seed;this.config=config;this.settings=config.roads();this.terrain=terrain;this.radius=config.world().radius();
    }
    private MacroSample sample(double x,double z) {
        if(operations>=edgeLimit)throw new EdgeBudget();
        if(++operations>settings.maximumOperations())throw failure(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED,"road operation budget exhausted", "operations",operations);
        // Cache integer column centres only, with a fixed capacity. Operation accounting is outside
        // the cache, so hitting/evicting a sample cannot change termination or geometry.
        int ix=(int)Math.floor(x),iz=(int)Math.floor(z);
        if(x!=ix+.5||z!=iz+.5)return terrain.sample(x,z);
        long key=RoadPlan.key(ix,iz);var value=samples.get(key);
        if(value!=null)return value;
        value=terrain.sample(x,z);
        if(samples.size()<100_000)samples.put(key,value);
        return value;
    }
    public RoadPlan plan(AdventurePlanView.SpawnPosition spawn,List<PlannedBiomePatch> patches,
                         List<PlannedStructurePlacement> structures,StructurePlanningCatalog catalog,
                         BiFunction<Integer,Integer,ContentId> biomeAt) {
        if(!settings.enabled())return RoadPlan.EMPTY;
        spawnX=(int)Math.floor(spawn.x());spawnZ=(int)Math.floor(spawn.z());spawnDeck=(int)Math.floor(sample(spawnX+.5,spawnZ+.5).groundSurface())-1;
        for(var p:structures)catalog.find(p).map(info->info.footprintAt(seed,p.anchorX(),p.anchorZ())).ifPresent(bounds ->
                reservations.add(new RoadPlan.Reservation(p.instanceId(),bounds)));
        reservations.sort(Comparator.comparing(RoadPlan.Reservation::instanceId));
        var nodes=new ArrayList<RoadPlan.Node>();nodes.add(new RoadPlan.Node("spawn",spawnX,spawnZ,true));
        // Repeated requirements for one biome share an exploration waypoint; required wins.
        var biomeConnections = new TreeMap<ContentId, Boolean>();
        for (var request : config.biomes().required()) if (request.road().enabled())
            biomeConnections.merge(request.id(), request.road().required(), (a,b) -> a || b);
        for(var entry:biomeConnections.entrySet()) {
            ContentId biome=entry.getKey();boolean required=entry.getValue();
            String id="biome/"+biome.value();Vec2 found=null;
            var candidates=patches.stream().filter(p->p.biomeId().equals(biome)).sorted(Comparator.comparing(PlannedBiomePatch::patchId)).toList();
            double nearest=Double.POSITIVE_INFINITY;
            for(var p:candidates) {
                int stride=Math.max(16,(int)Math.ceil(Math.sqrt((double)(p.maxXExclusive()-p.minX())*(p.maxZExclusive()-p.minZ()))/128));
                for(int z=p.minZ()+2;z<p.maxZExclusive();z+=stride)for(int x=p.minX()+2;x<p.maxXExclusive();x+=stride) {
                    double distance=StrictMath.hypot(x-spawnX,z-spawnZ);
                    if(distance<64||distance>=nearest||!p.contains(x,z))continue;
                    if(validPoint(x,z)&&biomeAt.apply(x,z).equals(biome)){found=new Vec2(x,z);nearest=distance;}
                }
            }
            if(found==null)missing(id,required,"NO_VALID_BIOME_WAYPOINT");
            else add(nodes,new RoadPlan.Node(id,(int)found.x(),(int)found.z(),required));
        }
        for(var requested:config.structures()) {
            if (!requested.road().enabled()) continue;

            var matches=structures.stream().filter(p->p.structureId().equals(requested.id())).sorted(Comparator.comparing(PlannedStructurePlacement::instanceId)).toList();
            if(matches.isEmpty()) {missing("structure/"+requested.id(),requested.road().required(),"MISSING_ACCESS_CONTRACT_OR_INSTANCE");continue;}
            for(var p:matches) {
                var info=catalog.find(p).orElse(null);
                if(info==null||info.roadAccess()==null||(info.footprint()==null&&info.templateFootprint()==null)) {
                    missing("structure/"+p.instanceId(),requested.road().required(),"MISSING_ACCESS_CONTRACT_OR_INSTANCE");continue;
                }
                String id="structure/"+p.instanceId();
                var points=RoadAccessCandidates.generate(info,seed,p,settings.width())
                        .stream().filter(v->validPoint((int)Math.floor(v.endpoint().x()),(int)Math.floor(v.endpoint().z()))).toList();
                if(points.isEmpty())missing(id,requested.road().required(),"NO_VALID_APPROACH");
                else {
                    var first=points.stream().min(Comparator.comparingDouble(v->StrictMath.hypot(v.endpoint().x()-spawnX,v.endpoint().z()-spawnZ))).orElseThrow();
                    int before=nodes.size();
                    add(nodes,new RoadPlan.Node(id,(int)Math.floor(first.endpoint().x()),(int)Math.floor(first.endpoint().z()),requested.road().required()));
                    if(nodes.size()>before) {
                        accessCandidates.put(id,points);
                    }
                  }
            }
        }
        nodes.subList(1,nodes.size()).sort(Comparator.comparing(RoadPlan.Node::id));
        int n=nodes.size();var parent=new int[n];for(int i=0;i<n;i++)parent[i]=i;
        var search=new RoadSearch(settings,this::sample,reservations,radius);
        var attempted=new HashSet<Long>();var failures=new TreeMap<String,String>();var candidates=new ArrayList<Candidate>();
        // Sparse nearest-neighbour graph, followed by bounded component repair. Search costs
        // rank alternatives for each outward connection; full construction may still reject an edge.
        var initialPairs=new TreeSet<Long>();
        for(int i=0;i<n;i++) {
            final int origin=i;
            var nearest=new ArrayList<Integer>();for(int j=0;j<n;j++)if(j!=i)nearest.add(j);
            nearest.sort(Comparator.comparingDouble((Integer j)->distance(nodes.get(origin),nodes.get(j))).thenComparingInt(Integer::intValue));
            for(int j:nearest.subList(0,Math.min(3,nearest.size())))initialPairs.add(pair(i,j));
            if(nodes.get(i).required())nearest.stream().filter(j->nodes.get(j).required()).limit(3).forEach(j->initialPairs.add(pair(origin,j)));
        }
        // Repair the required backbone before optional destinations can spend the remaining search allowance.
        var orderedPairs=initialPairs.stream().sorted(Comparator.comparingDouble((Long key)->
                distance(nodes.get((int)(key>>32)),nodes.get((int)(long)key))).thenComparingLong(Long::longValue)).toList();
        for(int phase=0;phase<2;phase++) {
            // Grow outwards from spawn and lock each entrance before evaluating its next edge.
            // Evaluating every pair first wastes the budget on opposite sides that cannot be joined.
            for(int pass=0;pass<n;pass++) {
                int count=routes.size();
                for(long pair:orderedPairs) {
                    int a=(int)(pair>>32),b=(int)pair;
                    if((nodes.get(a).required()&&nodes.get(b).required())!=(phase==0))continue;
                    boolean fromConnected=root(parent,a)==root(parent,0),toConnected=root(parent,b)==root(parent,0);
                    if(fromConnected==toConnected)continue;
                    var extra=new ArrayList<Candidate>();
                    evaluate(a,b,nodes,search,attempted,extra,failures);
                    candidates.addAll(extra);
                    commitCandidates(extra,nodes,parent,search,attempted,false,Double.POSITIVE_INFINITY);
                }
                if(routes.size()==count)break;
            }
            repair(nodes,parent,search,attempted,candidates,failures,phase==0);
        }
        // Only the spawn component is published. Disconnected optional islands are diagnostics,
        // never a visually plausible but inaccessible second road network.
        for(int i=1;i<n;i++)if(root(parent,i)!=root(parent,0))missing(nodes.get(i).id(),nodes.get(i).required(),failures.getOrDefault(nodes.get(i).id(),"DISCONNECTED_SEARCH_DOMAIN"));
        var connected=new HashSet<String>();for(int i=0;i<n;i++)if(root(parent,i)==root(parent,0))connected.add(nodes.get(i).id());
        var kept=routes.stream().filter(r->connected.contains(r.from())&&connected.contains(r.to())).toList();
        if(kept.size()!=routes.size()) {
            routes.clear();routes.addAll(kept);columns.clear();
            for(var r:routes) {var built=construct(r.points());if(!built.valid())throw failure(PlanningFailure.Code.EXECUTION_FAILED,"road component reconstruction failed","route",r.id());for(var c:built.columns())columns.put(RoadPlan.key(c.x(),c.z()),c);}
        }
        for(long key:orderedPairs) {
            int a=(int)(key>>32),b=(int)key;
            if(connected.contains(nodes.get(a).id())&&connected.contains(nodes.get(b).id())
                    &&routes.stream().noneMatch(r->r.from().equals(nodes.get(a).id())&&r.to().equals(nodes.get(b).id())))
                evaluate(a,b,nodes,search,attempted,candidates,failures);
        }
        double treeLength=routes.stream().mapToDouble(RoadPlan.Route::length).sum();
        commitCandidates(candidates.stream().filter(c->connected.contains(nodes.get(c.a()).id())&&connected.contains(nodes.get(c.b()).id())).toList(),nodes,parent,search,attempted,true,treeLength*settings.loopBudgetFraction());
        validateConnected(nodes.stream().filter(node->connected.contains(node.id())).toList());
        return new RoadPlan(nodes,routes,List.copyOf(columns.values()),reservations,skipped,operations);
    }
    private void repair(List<RoadPlan.Node> nodes,int[] parent,RoadSearch search,Set<Long> attempted,
                        List<Candidate> candidates,Map<String,String> failures,boolean requiredOnly) {
        int n=nodes.size();
        for(int attempt=0;attempt<n*3;attempt++) {
            boolean[] needed=new boolean[n];
            for(int i=0;i<n;i++)if(nodes.get(i).required()&&root(parent,i)!=root(parent,0))needed[root(parent,i)]=true;
            int a=-1,b=-1;double nearest=Double.POSITIVE_INFINITY;
            for(int i=0;i<n;i++)for(int j=i+1;j<n;j++)if(root(parent,i)!=root(parent,j)&&!attempted.contains(pair(i,j))) {
                if(requiredOnly&&!needed[root(parent,i)]&&!needed[root(parent,j)])continue;
                double d=distance(nodes.get(i),nodes.get(j));if(d<nearest){nearest=d;a=i;b=j;}
            }
            if(a<0)break;
            var extra=new ArrayList<Candidate>();evaluate(a,b,nodes,search,attempted,extra,failures);
            candidates.addAll(extra);commitCandidates(extra,nodes,parent,search,attempted,false,Double.POSITIVE_INFINITY);
        }
    }
    private boolean validPoint(int x,int z) {
        if(Math.abs(x)>radius||Math.abs(z)>radius)return false;
        for(var r:reservations)if(r.bounds().contains(x+.5,z+.5,settings.width()/2.+1))return false;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {var s=sample(x+dx+.5,z+dz+.5);if(s.wet()||s.hazardous())return false;}
        return true;
    }
    private void add(List<RoadPlan.Node> nodes,RoadPlan.Node node) {
        if(nodes.size()>=settings.maximumNodes())throw failure(PlanningFailure.Code.RESOURCE_LIMIT,"road node limit","node",node.id());
        if(!validPoint(node.x(),node.z())){missing(node.id(),node.required(),"INVALID_ENDPOINT");return;}
        // Coincident destinations can share the root/endpoint; retain an explicit diagnostic.
        if(nodes.stream().anyMatch(n->n.x()==node.x()&&n.z()==node.z())) {skipped.add(new RoadPlan.Skipped(node.id(),"COINCIDENT_ENDPOINT"));return;}
        nodes.add(node);
    }
    private void missing(String id,boolean required,String reason) {
        if(required)throw failure(reason.contains("BUDGET")?PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED:PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,
                "required road destination cannot be connected","node",id+":"+reason);
        skipped.add(new RoadPlan.Skipped(id,reason));
    }
    private record Candidate(int a,int b,List<Vec2> points,double cost) {}
    private void evaluate(int i,int j,List<RoadPlan.Node> nodes,RoadSearch search,Set<Long> attempted,List<Candidate> candidates,Map<String,String> failures) {
        int a=Math.min(i,j),b=Math.max(i,j);if(!attempted.add(pair(a,b)))return;
        record Pair(Access from,Access to) {}
        var pairs=new ArrayList<Pair>();
        for(var from:accessPoints(nodes.get(a)))for(var to:accessPoints(nodes.get(b)))pairs.add(new Pair(from,to));
        pairs.sort(Comparator.comparingDouble((Pair p)->RoadShape.distance(p.from().approach(),p.to().approach()))
                .thenComparingDouble(p->p.from().endpoint().x()).thenComparingDouble(p->p.from().endpoint().z())
                .thenComparingDouble(p->p.to().endpoint().x()).thenComparingDouble(p->p.to().endpoint().z()));
        // Reserve a quarter of the global sample allowance for rasterizing committed roads.
        // A difficult optional pair must not consume every later pair's search budget.
        edgeLimit=Math.min(settings.maximumOperations()*3/4,operations+PAIR_OPERATIONS);
        long pairLimit=edgeLimit;
        int before=candidates.size();
        try {
            // Probe cheap direct routes across the perimeter before an obstructed side spends A* budget.
            for(boolean directOnly:new boolean[]{true,false}) {
                if(!directOnly&&candidates.size()>before)break;
                for(var pair:pairs.subList(0,Math.min(MAX_ACCESS_PAIRS,pairs.size()))) {
                double lowerBound=RoadShape.distance(pair.from().approach(),pair.to().approach())
                        +RoadShape.distance(pair.from().endpoint(),pair.from().approach())+RoadShape.distance(pair.to().endpoint(),pair.to().approach());
                double best=candidates.subList(before,candidates.size()).stream().mapToDouble(Candidate::cost).min().orElse(Double.POSITIVE_INFINITY);
                if(lowerBound>=best)continue;
                if(operations>=pairLimit)throw new EdgeBudget();
                edgeLimit=Math.min(pairLimit,operations+(pairs.size()==1?PAIR_OPERATIONS:ACCESS_OPERATIONS));
                try { evaluateGeometry(a,b,pair.from(),pair.to(),nodes,search,candidates,failures,directOnly); }
                catch(EdgeBudget exhausted) {failures.put(nodes.get(b).id(),"SEARCH_BUDGET_EXHAUSTED");}
                }
            }
        }
        catch(EdgeBudget exhausted) {
            failures.put(nodes.get(a).id(),"SEARCH_BUDGET_EXHAUSTED");
            failures.put(nodes.get(b).id(),"SEARCH_BUDGET_EXHAUSTED");
        } finally {edgeLimit=Long.MAX_VALUE;}
    }
    private void evaluateGeometry(int a,int b,Access from,Access to,List<RoadPlan.Node> nodes,RoadSearch search,
                                  List<Candidate> candidates,Map<String,String> failures,boolean directOnly) {
        double localCost=search.edge(from.endpoint(),from.approach(),true)+search.edge(to.approach(),to.endpoint(),true);
        if(!Double.isFinite(localCost)) {failures.put(nodes.get(b).id(),"LOCAL_CONNECTOR_BLOCKED");return;}
        var result=directOnly?search.findDirect(from.approach(),to.approach()):search.find(from.approach(),to.approach(),false);
        if(result.valid()) {
            var shaped=withConnectors(RoadShape.shape(result.points(),seed,nodes.get(a).id()+"/"+nodes.get(b).id(),settings,search::dryLegal),from,to);
            shaped=RoadJunctions.merge(shaped,routes,settings.maximumBendDetour());
            var built=construct(shaped);
            if(!built.valid()) {
                shaped=RoadJunctions.merge(withConnectors(result.points(),from,to),routes,settings.maximumBendDetour());built=construct(shaped);

            }
            if(result.valid()&&built.valid()){candidates.add(new Candidate(a,b,shaped,result.cost()+localCost));return;}
            failures.put(nodes.get(b).id(),built.failure());
        } else failures.put(nodes.get(b).id(),result.failure());
    }
    private static List<Vec2> withConnectors(List<Vec2> macro,Access from,Access to) {
        var result=new ArrayList<Vec2>();
        if(!from.endpoint().equals(from.approach()))result.add(from.endpoint());
        result.addAll(macro);
        if(!to.endpoint().equals(to.approach()))result.add(to.endpoint());
        return List.copyOf(result);
    }

    private void commitCandidates(List<Candidate> candidates,List<RoadPlan.Node> nodes,int[] parent,RoadSearch search,Set<Long> attempted,boolean loops,double budget) {
        double spent=0;
        for(var c:candidates.stream().sorted(Comparator.comparingDouble(Candidate::cost).thenComparingInt(Candidate::a).thenComparingInt(Candidate::b).thenComparingDouble(c->c.points().getFirst().x()).thenComparingDouble(c->c.points().getFirst().z()).thenComparingDouble(c->c.points().getLast().x()).thenComparingDouble(c->c.points().getLast().z())).toList()) {
            String from=nodes.get(c.a()).id(),to=nodes.get(c.b()).id(),id=from+"->"+to;
            if(routes.stream().anyMatch(r->r.id().equals(id)))continue;
            if(!loops&&root(parent,c.a())==root(parent,c.b()))continue;
            var aligned=align(c,nodes,search);
            if(aligned==null)continue;
            var path=aligned.points();var built=aligned.built();
            double length=RoadShape.length(path);
            if(loops&&(spent+length>budget||networkDistance(nodes.get(c.a()),nodes.get(c.b()))<=length*1.5))continue;
            lock(nodes,c.a(),path.getFirst(),attempted);
            lock(nodes,c.b(),path.getLast(),attempted);
            for(var cell:built.columns())columns.put(RoadPlan.key(cell.x(),cell.z()),cell);
            routes.add(new RoadPlan.Route(id,from,to,path,length));
            parent[root(parent,c.b())]=root(parent,c.a());spent+=length;
        }
    }
    private record Aligned(List<Vec2> points,RoadConstruction.Result built) {}
    private Aligned align(Candidate candidate,List<RoadPlan.Node> nodes,RoadSearch search) {
        var from=nodes.get(candidate.a());var to=nodes.get(candidate.b());
        var start=candidate.points().getFirst();var end=candidate.points().getLast();
        if(compatible(from,start)&&compatible(to,end)) {
            var path=RoadJunctions.merge(candidate.points(),routes,settings.maximumBendDetour());
            var built=construct(path);return built.valid()?new Aligned(path,built):null;
        }
        // A locked entrance changes the path problem. Search to that entrance again rather than
        // appending an axis-aligned rectangle after curve shaping.
        Access source=accessPoints(from).stream().filter(p->p.endpoint().equals(compatible(from,start)?start:point(from))).findFirst().orElseThrow();
        Access target=accessPoints(to).stream().filter(p->p.endpoint().equals(compatible(to,end)?end:point(to))).findFirst().orElseThrow();
        edgeLimit=Math.min(settings.maximumOperations()*3/4,operations+PAIR_OPERATIONS);
        try {
            var replacement=new ArrayList<Candidate>();
            var failures=new TreeMap<String,String>();
            evaluateGeometry(candidate.a(),candidate.b(),source,target,nodes,search,replacement,failures,true);
            if(replacement.isEmpty())evaluateGeometry(candidate.a(),candidate.b(),source,target,nodes,search,replacement,failures,false);
            if(!replacement.isEmpty()) {
                var path=replacement.getFirst().points();var built=construct(path);
                if(built.valid())return new Aligned(path,built);
            }
        } catch(EdgeBudget exhausted) {
            return null;
        } finally {edgeLimit=Long.MAX_VALUE;}
        return null;
    }
    private List<Access> accessPoints(RoadPlan.Node node) {
        var values=accessCandidates.getOrDefault(node.id(),List.of(Access.point(point(node))));
        return lockedAccess.contains(node.id())?values.stream().filter(a->a.endpoint().equals(point(node))).toList():values;
    }
    private boolean compatible(RoadPlan.Node node,Vec2 endpoint) {
        return !accessCandidates.containsKey(node.id())||!lockedAccess.contains(node.id())||point(node).equals(endpoint);
    }
    private void lock(List<RoadPlan.Node> nodes,int index,Vec2 endpoint,Set<Long> attempted) {
        var old=nodes.get(index);
        if(!accessCandidates.containsKey(old.id())||!lockedAccess.add(old.id()))return;
        nodes.set(index,new RoadPlan.Node(old.id(),(int)Math.floor(endpoint.x()),(int)Math.floor(endpoint.z()),old.required()));
        // Reopen pairs evaluated with a now-invalid alternative endpoint, within the repair bound.
        for(int i=0;i<nodes.size();i++)if(i!=index)attempted.remove(pair(index,i));
    }
    private RoadConstruction.Result construct(List<Vec2> path) {return RoadConstruction.build(path,settings,this::sample,reservations,columns,spawnX,spawnZ,spawnDeck);}
    private double networkDistance(RoadPlan.Node from,RoadPlan.Node to) {
        // Physical junctions shorten travel even when they are not destination nodes. Cardinal
        // raster distance is a conservative travel estimate; the loop still needs a substantial gain.
        long start=RoadPlan.key(from.x(),from.z()),end=RoadPlan.key(to.x(),to.z());
        if(!columns.containsKey(start)||!columns.containsKey(end))return Double.POSITIVE_INFINITY;
        var distance=new HashMap<Long,Integer>();var todo=new ArrayDeque<Long>();
        distance.put(start,0);todo.add(start);
        int[][] offsets={{1,0},{-1,0},{0,1},{0,-1}};
        while(!todo.isEmpty()) {
            long key=todo.remove();int steps=distance.get(key);if(key==end)return steps;
            var c=columns.get(key);
            for(var offset:offsets) {
                long next=RoadPlan.key(c.x()+offset[0],c.z()+offset[1]);var neighbor=columns.get(next);
                if(neighbor!=null&&Math.abs(neighbor.deckY()-c.deckY())<=1&&!distance.containsKey(next)) {
                    distance.put(next,steps+1);todo.add(next);
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }
    private void validateConnected(List<RoadPlan.Node> nodes) {
        if(routes.isEmpty())return;
        long start=RoadPlan.key(spawnX,spawnZ);if(!columns.containsKey(start))throw failure(PlanningFailure.Code.EXECUTION_FAILED,"road missing spawn column","spawn",start);
        var seen=new HashSet<Long>();var todo=new ArrayDeque<Long>();seen.add(start);todo.add(start);
        int[][] offsets={{1,0},{-1,0},{0,1},{0,-1}};
        while(!todo.isEmpty()) {
            var c=columns.get(todo.remove());
            for(var d:offsets){long key=RoadPlan.key(c.x()+d[0],c.z()+d[1]);var other=columns.get(key);if(other!=null&&Math.abs(other.deckY()-c.deckY())<=1&&seen.add(key))todo.add(key);}
        }
        for(var node:nodes)if(!seen.contains(RoadPlan.key(node.x(),node.z())))throw failure(PlanningFailure.Code.EXECUTION_FAILED,"quantized road is disconnected","node",node.id());
    }
    private static long pair(int a,int b){return ((long)Math.min(a,b)<<32)|Math.max(a,b);}
    private static Vec2 point(RoadPlan.Node node){return new Vec2(node.x()+.5,node.z()+.5);}
    private static double distance(RoadPlan.Node a,RoadPlan.Node b){return RoadShape.distance(point(a),point(b));}
    private static int root(int[] parent,int i){while(parent[i]!=i)i=parent[i];return i;}
    private static PlanningFailure failure(PlanningFailure.Code code,String message,String key,Object value){return new PlanningFailure(code,FailureStage.ROADS,message,Map.of(key,value));}
}
