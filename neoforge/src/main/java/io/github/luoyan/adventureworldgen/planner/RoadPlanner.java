package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.RoadSettings;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
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
        spawnX=(int)Math.floor(spawn.x());spawnZ=(int)Math.floor(spawn.z());spawnDeck=(int)Math.floor(spawn.y())-1;
        for(var p:structures)catalog.find(p.structureId()).map(StructurePlanningInfo::roadAccess).ifPresent(access ->
                reservations.add(new RoadPlan.Reservation(p.instanceId(),p.anchorX(),p.anchorZ(),access.exclusionRadius())));
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
            var info=catalog.find(requested.id()).orElse(null);
            var matches=structures.stream().filter(p->p.structureId().equals(requested.id())).sorted(Comparator.comparing(PlannedStructurePlacement::instanceId)).toList();
            if(info==null||info.roadAccess()==null||matches.isEmpty()) {missing("structure/"+requested.id(),requested.road().required(),"MISSING_ACCESS_CONTRACT_OR_INSTANCE");continue;}
            for(var p:matches) {
                Vec2 found=null;var access=info.roadAccess();
                // Approach from the spawn-facing side first, keeping the complete road outside the envelope.
                double angle=StrictMath.atan2(spawnZ-p.anchorZ(),spawnX-p.anchorX());
                for(int i=0;i<8&&found==null;i++) {
                    int x=p.anchorX()+(int)Math.round(StrictMath.cos(angle+i*Math.PI/4)*access.approachDistance());
                    int z=p.anchorZ()+(int)Math.round(StrictMath.sin(angle+i*Math.PI/4)*access.approachDistance());
                    if(validPoint(x,z))found=new Vec2(x,z);
                }
                String id="structure/"+p.instanceId();
                if(found==null)missing(id,requested.road().required(),"NO_VALID_APPROACH");
                else add(nodes,new RoadPlan.Node(id,(int)found.x(),(int)found.z(),requested.road().required()));
            }
        }
        nodes.subList(1,nodes.size()).sort(Comparator.comparing(RoadPlan.Node::id));
        int n=nodes.size();var parent=new int[n];for(int i=0;i<n;i++)parent[i]=i;
        var search=new RoadSearch(settings,this::sample,reservations,radius);
        var attempted=new HashSet<Long>();var failures=new TreeMap<String,String>();var candidates=new ArrayList<Candidate>();
        // Sparse nearest-neighbour graph, followed by bounded component repair. Evaluated edges are
        // sorted by construction cost before each Kruskal pass, with stable endpoint tie breaks.
        for(int i=0;i<n;i++) {
            final int origin=i;
            var nearest=new ArrayList<Integer>();for(int j=0;j<n;j++)if(j!=i)nearest.add(j);
            nearest.sort(Comparator.comparingDouble((Integer j)->distance(nodes.get(origin),nodes.get(j))).thenComparingInt(Integer::intValue));
            for(int j:nearest.subList(0,Math.min(3,nearest.size())))evaluate(i,j,nodes,search,attempted,candidates,failures);
        }
        commitCandidates(candidates,nodes,parent,search,false,Double.POSITIVE_INFINITY);
        for(int attempt=0;attempt<n*3;attempt++) {
            int a=-1,b=-1;double distance=Double.POSITIVE_INFINITY;
            for(int i=0;i<n;i++)for(int j=i+1;j<n;j++)if(root(parent,i)!=root(parent,j)&&!attempted.contains(pair(i,j))) {
                double d=distance(nodes.get(i),nodes.get(j));if(d<distance){distance=d;a=i;b=j;}
            }
            if(a<0)break;
            var extra=new ArrayList<Candidate>();evaluate(a,b,nodes,search,attempted,extra,failures);
            candidates.addAll(extra);commitCandidates(extra,nodes,parent,search,false,Double.POSITIVE_INFINITY);
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
        double treeLength=routes.stream().mapToDouble(RoadPlan.Route::length).sum();
        commitCandidates(candidates.stream().filter(c->connected.contains(nodes.get(c.a()).id())&&connected.contains(nodes.get(c.b()).id())).toList(),nodes,parent,search,true,treeLength*settings.loopBudgetFraction());
        validateConnected(nodes.stream().filter(node->connected.contains(node.id())).toList());
        return new RoadPlan(nodes,routes,List.copyOf(columns.values()),reservations,skipped,operations);
    }
    private boolean validPoint(int x,int z) {
        if(Math.abs(x)>radius||Math.abs(z)>radius)return false;
        for(var r:reservations)if(Math.abs(x-r.x())<=r.radius()+settings.width()&&Math.abs(z-r.z())<=r.radius()+settings.width())return false;
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
        Vec2 from=point(nodes.get(a)),to=point(nodes.get(b));
        // Reserve a quarter of the global sample allowance for rasterizing committed roads.
        // A difficult optional pair must not consume every later pair's search budget.
        edgeLimit=Math.min(settings.maximumOperations()*3/4,operations+750_000);
        try { evaluateGeometry(a,b,from,to,nodes,search,candidates,failures); }
        catch(EdgeBudget exhausted) {
            failures.put(nodes.get(a).id(),"SEARCH_BUDGET_EXHAUSTED");
            failures.put(nodes.get(b).id(),"SEARCH_BUDGET_EXHAUSTED");
        } finally {edgeLimit=Long.MAX_VALUE;}
    }
    private void evaluateGeometry(int a,int b,Vec2 from,Vec2 to,List<RoadPlan.Node> nodes,RoadSearch search,
                                  List<Candidate> candidates,Map<String,String> failures) {
        var result=search.find(from,to,true);
        if(result.valid()) {
            var shaped=RoadShape.shape(result.points(),seed,nodes.get(a).id()+"/"+nodes.get(b).id(),settings,search::dryLegal);
            var built=construct(shaped);
            if(!built.valid()) {
                built=construct(result.points());shaped=result.points();
                if(!built.valid()) {
                    result=search.find(from,to,false);
                    if(result.valid()){shaped=RoadShape.shape(result.points(),seed,nodes.get(a).id()+"/"+nodes.get(b).id(),settings,search::dryLegal);built=construct(shaped);}
                }
            }
            if(result.valid()&&built.valid()){candidates.add(new Candidate(a,b,shaped,result.cost()));return;}
            failures.put(nodes.get(b).id(),built.failure());
        } else failures.put(nodes.get(b).id(),result.failure());
    }

    private void commitCandidates(List<Candidate> candidates,List<RoadPlan.Node> nodes,int[] parent,RoadSearch search,boolean loops,double budget) {
        double spent=0;
        for(var c:candidates.stream().sorted(Comparator.comparingDouble(Candidate::cost).thenComparingInt(Candidate::a).thenComparingInt(Candidate::b)).toList()) {
            String from=nodes.get(c.a()).id(),to=nodes.get(c.b()).id(),id=from+"->"+to;
            if(routes.stream().anyMatch(r->r.id().equals(id)))continue;
            if(!loops&&root(parent,c.a())==root(parent,c.b()))continue;
            double length=RoadShape.length(c.points());
            if(loops&&(spent+length>budget||networkDistance(from,to)<=length*1.5))continue;
            var built=construct(c.points());if(!built.valid())continue;
            for(var cell:built.columns())columns.put(RoadPlan.key(cell.x(),cell.z()),cell);
            routes.add(new RoadPlan.Route(id,from,to,c.points(),length));
            parent[root(parent,c.b())]=root(parent,c.a());spent+=length;
        }
    }
    private RoadConstruction.Result construct(List<Vec2> path) {return RoadConstruction.build(path,settings,this::sample,reservations,columns,spawnX,spawnZ,spawnDeck);}
    private double networkDistance(String from,String to) {
        var best=new TreeMap<String,Double>();best.put(from,0.);var done=new HashSet<String>();
        while(true) {
            String at=null;double cost=Double.POSITIVE_INFINITY;
            for(var e:best.entrySet())if(!done.contains(e.getKey())&&e.getValue()<cost){at=e.getKey();cost=e.getValue();}
            if(at==null)return cost;if(at.equals(to))return cost;done.add(at);
            for(var r:routes){String next=r.from().equals(at)?r.to():r.to().equals(at)?r.from():null;if(next!=null)best.merge(next,cost+r.length(),Math::min);}
        }
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
