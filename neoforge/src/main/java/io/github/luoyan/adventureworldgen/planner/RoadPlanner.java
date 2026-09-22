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
    private final RoadWorkBudget work;
    private final List<RoadPlan.Skipped> skipped=new ArrayList<>();
    private final List<RoadPlan.Reservation> reservations=new ArrayList<>();
    private final RoadNetwork network=new RoadNetwork();
    private final List<RoadPlan.Route> routes=new ArrayList<>();
    private final Map<String,Double> travel=new HashMap<>();
    private final Map<String,String> failures=new HashMap<>();
    private final Map<String,List<String>> attempts=new TreeMap<>();
    public Map<String,Map<String,Long>> workReport(){return work.report();}
    public Map<String,List<String>> attemptReport(){var copy=new TreeMap<String,List<String>>();attempts.forEach((id,reasons)->copy.put(id,List.copyOf(reasons)));return Collections.unmodifiableMap(copy);}
    private void rejected(String target,String reason){failures.put(target,reason);var list=attempts.computeIfAbsent(target,ignored->new ArrayList<>());if(list.size()<24)list.add(reason);}
    private record SamplePoint(double x,double z) {}
    private final Map<SamplePoint,MacroSample> samples=new LinkedHashMap<>(1024,.75f,true);
    private int spawnX,spawnZ,spawnDeck;
    private final Map<String,List<Access>> accessCandidates=new TreeMap<>();
    private final Set<String> lockedAccess=new HashSet<>();

    public RoadPlanner(long seed,AdventureWorldConfig config,MacroTerrain terrain) {
        this(seed,config,terrain,RoadWorkControl.AUTOMATIC);
    }
    public RoadPlanner(long seed,AdventureWorldConfig config,MacroTerrain terrain,RoadWorkControl control) {
        this(seed,config,terrain,control,PlanningExecution.SERIAL);
    }
    public RoadPlanner(long seed,AdventureWorldConfig config,MacroTerrain terrain,RoadWorkControl control,PlanningExecution execution) {
        Objects.requireNonNull(execution);Objects.requireNonNull(control);
        this.seed=seed;this.config=config;this.settings=config.roads();this.terrain=terrain;this.radius=config.world().radius();
        this.work=new RoadWorkBudget(settings.maximumOperations(),control);
    }
    private void replaySamples(long count) {
        // Cache hits consume the same logical work: cache size cannot change the finite search domain.
        for(long i=0;i<count;i++){work.operation();operations++;}
    }
    private MacroSample sample(double x,double z) {
        work.operation();
        operations++;
        // Exact coordinates include the off-grid shoulders of diagonal edges. Heading states
        // revisit those samples heavily; quantizing them would change terrain and is forbidden.
        var key=new SamplePoint(x,z);var value=samples.get(key);
        if(value!=null)return value;
        value=terrain.sample(x,z);
        samples.put(key,value);
        if(samples.size()>100_000)samples.remove(samples.keySet().iterator().next());
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
            String id="biome/"+biome.value();work.task("connection/"+id);
            try {
                Vec2 found=null;
                var candidates=patches.stream().filter(p->p.biomeId().equals(biome)).sorted(Comparator.comparing(PlannedBiomePatch::patchId)).toList();
                double nearest=Double.POSITIVE_INFINITY;
                for(var p:candidates) {
                    int stride=Math.max(16,(int)Math.ceil(Math.sqrt((double)(p.maxXExclusive()-p.minX())*(p.maxZExclusive()-p.minZ()))/128));
                    for(int z=p.minZ()+2;z<p.maxZExclusive();z+=stride)for(int x=p.minX()+2;x<p.maxXExclusive();x+=stride) {
                        work.visit();double distance=StrictMath.hypot(x-spawnX,z-spawnZ);
                        if(distance<64||distance>=nearest||!p.contains(x,z))continue;
                        if(validPoint(x,z)&&biomeAt.apply(x,z).equals(biome)){found=new Vec2(x,z);nearest=distance;}
                    }
                }
                if(found==null)missing(id,required,"NO_VALID_BIOME_WAYPOINT");
                else add(nodes,new RoadPlan.Node(id,(int)found.x(),(int)found.z(),required));
            } catch(RoadWorkBudget.Limit limit){missing(id,required,limit.getMessage());}
        }
        for(var requested:config.structures()) {
            if (!requested.road().enabled()) continue;

            var matches=structures.stream().filter(p->p.structureId().equals(requested.id())).sorted(Comparator.comparing(PlannedStructurePlacement::instanceId)).toList();
            if(matches.isEmpty()) {missing("structure/"+requested.id(),requested.road().required(),"MISSING_ACCESS_CONTRACT_OR_INSTANCE");continue;}
            for(var p:matches) {
                work.task("connection/structure/"+p.instanceId());
                try {
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
                        add(nodes,new RoadPlan.Node(id,(int)Math.floor(first.endpoint().x()),(int)Math.floor(first.endpoint().z()),requested.road().required()));
                        if(nodes.stream().anyMatch(node->node.id().equals(id))) {
                            accessCandidates.put(id,points);
                        }
                      }
                } catch(RoadWorkBudget.Limit limit){missing("structure/"+p.instanceId(),requested.road().required(),limit.getMessage());}
            }
        }
        nodes.subList(1,nodes.size()).sort(Comparator.comparing(RoadPlan.Node::id));
        var pending=new ArrayList<>(nodes.subList(1,nodes.size()));nodes.subList(1,nodes.size()).clear();
        travel.put("spawn",0.);
        var search=new RoadSearch(settings,this::sample,reservations,radius,work,this::replaySamples);
        for(boolean required:new boolean[]{true,false}) {
            var selected=new ArrayList<>(pending.stream().filter(n->n.required()==required).toList());
            for(int pass=0;pass<2&&!selected.isEmpty();pass++) {
                var deferred=new ArrayList<RoadPlan.Node>();
                while(!selected.isEmpty()) {
                    // Re-rank against the current connected component, with a stable tie break.
                    selected.sort(Comparator.comparingDouble((RoadPlan.Node n)->nodes.stream().mapToDouble(p->distance(n,p)).min().orElseThrow())
                            .thenComparing(RoadPlan.Node::id));
                    var target=selected.removeFirst();work.task("connection/"+target.id());
                    try {if(!connect(target,nodes,search))deferred.add(target);}
                    catch(RoadWorkBudget.Limit limit){failures.put(target.id(),limit.getMessage());deferred.add(target);}
                }
                selected=deferred;
            }
            for(var target:selected)missing(target.id(),target.required(),failures.getOrDefault(target.id(),"NO_ROUTE_IN_TEMPLATE_DOMAIN"));
        }
        addLoops(nodes,search);
        // Publication has its own bounded output-size pass, never another geometry search.
        work.task("publication");
        try {return RoadTopology.freeze(nodes,routes,network.columns(),reservations,skipped,operations,network.supports(),settings.maximumNodes(),work);}
        catch(RoadWorkBudget.Limit limit){throw failure(PlanningFailure.Code.RESOURCE_LIMIT,"road publication budget exhausted","reason",limit.getMessage());}
    }
    private record Source(String id,Access access,int y,double distance) {}
    private record Pair(Source source,Access target,double score) {}
    private List<Source> sources(RoadPlan.Node target,List<RoadPlan.Node> nodes) {
        var choices=new ArrayList<Source>();var destination=point(target);
        for(var node:nodes) {
            var access=accessPoints(node).getFirst();var c=network.at(access.endpoint(),node.y());
            int y=c==null?spawnDeck:c.deckY();
            choices.add(new Source(node.id(),access,y,travel.getOrDefault(node.id(),0.)));
        }
        for(var segment:network.nearby(destination,work)) {
            var a=segment.a();var b=segment.b();double dx=b.x()-a.x(),dz=b.z()-a.z(),length2=dx*dx+dz*dz;
            if(length2<1e-8)continue;
            double projection=Math.clamp(((destination.x()-a.x())*dx+(destination.z()-a.z())*dz)/length2,0,1);
            for(double shift:new double[]{0,-16,16}) {
                work.visit();double t=Math.clamp(projection+shift/Math.sqrt(length2),0,1);
                var p=new Vec2(Math.floor(a.x()+dx*t)+.5,Math.floor(a.z()+dz*t)+.5);var cell=network.at(p,Integer.MIN_VALUE);
                if(cell==null||cell.shoulder()||cell.kind()!=RoadPlan.Kind.GROUND)continue;
                if(nodes.stream().anyMatch(n->RoadShape.distance(point(n),p)<8))continue;
                String id="attachment/"+cell.x()+"/"+cell.deckY()+"/"+cell.z();
                choices.add(new Source(id,Access.point(p),cell.deckY(),segment.startDistance()+Math.sqrt(length2)*t));
            }
        }
        choices.sort(Comparator.comparingDouble((Source p)->RoadShape.distance(p.access.approach(),destination)
                        +PlanningPolicy.CURRENT.roadTravelWeight()*p.distance).thenComparing(Source::id));
        var result=new ArrayList<Source>();
        for(var choice:choices) {
            if(result.stream().anyMatch(s->RoadShape.distance(s.access.endpoint(),choice.access.endpoint())<16))continue;
            result.add(choice);if(result.size()>=work.policy().attachments())break;
        }
        return result;
    }
    private boolean connect(RoadPlan.Node target,List<RoadPlan.Node> nodes,RoadSearch search) {
        // A waypoint already on a connected ground deck needs an explicit node, not a zero-length road.
        var covered=network.at(point(target),Integer.MIN_VALUE);
        if(!accessCandidates.containsKey(target.id())&&covered!=null&&!covered.shoulder()&&covered.kind()==RoadPlan.Kind.GROUND) {
            if(nodes.size()>=settings.maximumNodes()){failures.put(target.id(),"RESOURCE_LIMIT_NODES");return false;}
            var source=sources(target,nodes).getFirst();nodes.add(target);travel.put(target.id(),source.distance+RoadShape.distance(source.access.endpoint(),point(target)));return true;
        }
        var pairs=new ArrayList<Pair>();
        for(var source:sources(target,nodes))for(var access:accessPoints(target)) {
            if(!Double.isFinite(search.edge(source.access.endpoint(),source.access.approach(),true))
                    ||!Double.isFinite(search.edge(access.endpoint(),access.approach(),true)))continue;
            double score=search.estimate(List.of(source.access.approach(),access.approach()));
            // A blocked direct path can still be repaired; retain it behind cheap legal sketches.
            if(!Double.isFinite(score))score=1_000_000+RoadShape.distance(source.access.approach(),access.approach());
            pairs.add(new Pair(source,access,score+PlanningPolicy.CURRENT.roadTravelWeight()*source.distance));
        }
        pairs.sort(Comparator.comparingDouble(Pair::score).thenComparing(p->p.source.id)
                .thenComparingDouble(p->p.target.endpoint().x()).thenComparingDouble(p->p.target.endpoint().z()));
        // Entrance alternatives share exactly the same candidate and validation ledger.
        var chosen=new ArrayList<Pair>();var directions=new HashSet<String>();
        for(var pair:pairs) {
            var endpoint=pair.target.endpoint();var approach=pair.target.approach();
            String direction=Math.signum(approach.x()-endpoint.x())+"/"+Math.signum(approach.z()-endpoint.z());
            if(directions.add(direction))chosen.add(pair);
            if(chosen.size()>=work.policy().attachments())break;
        }
        for(var pair:pairs)if(chosen.size()<work.policy().attachments()&&!chosen.contains(pair))chosen.add(pair);
        for(var pair:chosen) {
            var raw=dense(List.of(pair.source.access.approach(),pair.target.approach()));
            if(raw.isEmpty())continue;
            var path=withConnectors(raw,pair.source.access,pair.target);
            if(!work.candidate(RoadShape.length(path))){failures.put(target.id(),"RESOURCE_LIMIT_CANDIDATES_OR_LENGTH");return false;}
            if(!Double.isFinite(search.estimate(path))||!search.groundEnvelope(path))continue;
            var shaped=withConnectors(RoadShape.shape(raw,seed,pair.source.id+"/"+target.id(),settings,search::dryLegal),pair.source.access,pair.target);
            if(work.validations()>=2)continue;
            if(ground(target,nodes,pair,shaped,search,false))return true;
        }
        if(!chosen.isEmpty()) {
            var pair=chosen.getFirst();
            for(var sketch:search.detours(pair.source.access.approach(),pair.target.approach())) {
                var sampled=dense(sketch);if(sampled.size()<2)continue;
                var raw=withConnectors(sampled,pair.source.access,pair.target);
                if(!work.candidate(RoadShape.length(raw)))break;
                if(!Double.isFinite(search.estimate(raw))||!search.groundEnvelope(raw))continue;
                // Keep a validation available for a coarse terrain corridor if this template fails.
                if(work.validations()>=work.policy().validations()-1)break;
                var shaped=withConnectors(RoadShape.shape(dense(sketch),seed,pair.source.id+"/"+target.id(),settings,search::dryLegal),pair.source.access,pair.target);
                if(ground(target,nodes,pair,shaped,search,false))return true;
            }
        }
        // Large height differences use the hillside templates before spending the coarse search.
        for(var pair:chosen) {
            int ty=(int)Math.floor(sample(pair.target.endpoint().x(),pair.target.endpoint().z()).groundSurface())-1;
            if(Math.abs(ty-pair.source.y)<=settings.maximumEarthwork()*2
                    ||Math.abs(ty-pair.source.y)/Math.max(1,RoadShape.distance(pair.source.access.endpoint(),pair.target.endpoint()))<PlanningPolicy.CURRENT.preferredGrade()*.75)continue;
            if(boardwalk(target,nodes,pair,ty))return true;
            if(work.candidates()>=work.policy().candidates())return false;
        }
        for(var pair:chosen) {
            var found=search.find(pair.source.access.approach(),pair.target.approach(),false);
            if(!found.valid()){rejected(target.id(),found.failure()+" from "+pair.source.access.approach()+" to "+pair.target.approach());continue;}
            var path=withConnectors(dense(found.points()),pair.source.access,pair.target);
            if(!work.candidate(RoadShape.length(path))){failures.put(target.id(),"RESOURCE_LIMIT_CANDIDATES_OR_LENGTH");return false;}
            // Round only within verified dry corridors, then validate the whole resulting raster.
            path=withConnectors(RoadShape.shape(found.points(),seed,pair.source.id+"/"+target.id(),settings,search::dryLegal),pair.source.access,pair.target);
            if(ground(target,nodes,pair,path,search,true))return true;
        }
        return false;
    }
    private List<Vec2> dense(List<Vec2> path) {
        if(RoadShape.length(path)>work.policy().routeLength())return List.of();
        var result=new ArrayList<Vec2>();result.add(path.getFirst());
        for(int i=1;i<path.size();i++) {
            var a=path.get(i-1);var b=path.get(i);int count=Math.max(1,(int)Math.ceil(RoadShape.distance(a,b)/8));
            for(int k=1;k<=count;k++){work.visit();double t=k/(double)count;result.add(new Vec2(a.x()+(b.x()-a.x())*t,a.z()+(b.z()-a.z())*t));}
        }
        return result;
    }
    private boolean ground(RoadPlan.Node target,List<RoadPlan.Node> nodes,Pair pair,List<Vec2> path,RoadSearch search,boolean repairAllowed) {
        return ground(target,nodes,pair,path,search,repairAllowed,work.policy().routeLength());
    }
    private boolean ground(RoadPlan.Node target,List<RoadPlan.Node> nodes,Pair pair,List<Vec2> path,RoadSearch search,boolean repairAllowed,double maximumLength) {
        for(int attempt=0;attempt<=work.policy().repairs();attempt++) {
            if(nodes.stream().noneMatch(n->n.id().equals(target.id()))) {
                // Preserve the target's immutable outward connector. Required branches need
                // only their final connection to the existing component, not an incidental loop.
                int last=path.size()-(pair.target.endpoint().equals(pair.target.approach())?1:2);
                var rejoin=network.lastGroundContact(path,last,Math.max(1,settings.width()/2),work);
                if(rejoin!=null) {
                    var c=rejoin.column();var p=rejoin.path().getFirst();
                    String id=nodes.stream().filter(n->n.x()==c.x()&&n.z()==c.z()&&(n.y()==Integer.MIN_VALUE||n.y()==c.deckY()))
                            .map(RoadPlan.Node::id).findFirst().orElse("attachment/"+c.x()+"/"+c.deckY()+"/"+c.z());
                    double distance=network.distance(new Vec2(spawnX+.5,spawnZ+.5),spawnDeck,p,c.deckY(),work);
                    pair=new Pair(new Source(id,Access.point(p),c.deckY(),distance),pair.target,pair.score);
                    path=rejoin.path();
                }
            }
            if(RoadShape.length(path)>maximumLength)return false;
            if(nodes.stream().anyMatch(n->n.id().equals(target.id()))&&network.hasUnhelpfulExcursion(path,work))return false;
            if(!work.validation()){failures.put(target.id(),"RESOURCE_LIMIT_VALIDATIONS");return false;}
            RoadConstruction.Result built;
            try(var phase=work.phase("construction")) {
                built=RoadConstruction.build(path,settings,this::sample,reservations,network.ground,spawnX,spawnZ,spawnDeck,work);
            }
            if(built.valid()) {
                var reason=network.validate(built.columns(),List.of(),pair.source.access.endpoint(),pair.source.y,settings.maximumColumns(),work);
                if(reason==null)return commit(target,nodes,pair,path,List.of(),built.columns(),List.of(),RoadPlan.Kind.GROUND);
                failures.put(target.id(),reason);return false;
            }
            rejected(target.id(),built.failure()+" at "+built.failurePoint()+" to "+pair.target.endpoint());
            if(!repairAllowed||built.failure().startsWith("RESOURCE_LIMIT"))return false;
            if(work.validations()>=work.policy().validations())return false;
            var repair=search.repair(path,built.failurePoint());if(!repair.valid())return false;
            // The outward structure connector is immutable during local repairs.
            if(!connectorIntact(repair.points(),pair.source.access,true)||!connectorIntact(repair.points(),pair.target,false))return false;
            path=repair.points();
        }
        return false;
    }
    private static boolean connectorIntact(List<Vec2> path,Access access,boolean start) {
        if(access.endpoint().equals(access.approach()))return true;
        return start?path.getFirst().equals(access.endpoint())&&path.get(1).equals(access.approach())
                :path.getLast().equals(access.endpoint())&&path.get(path.size()-2).equals(access.approach());
    }
    private boolean boardwalk(RoadPlan.Node target,List<RoadPlan.Node> nodes,Pair pair,int ty) {
        // Boardwalk sampling uses the same ledger, including cache hits and support checks.
        var planner=new BoardwalkPlanner(settings,terrain,reservations,radius,work);
        try {
            for(var sketch:planner.templates(pair.source.access.approach(),pair.target.approach(),pair.source.y,ty)) {
                var path=withConnectors(sketch,pair.source.access,pair.target);
                if(!work.candidate(RoadShape.length(path))){failures.put(target.id(),"RESOURCE_LIMIT_CANDIDATES_OR_LENGTH");return false;}
                var geometry=planner.profile(path,pair.source.y,ty);if(geometry==null)continue;
                if(!work.validation()){failures.put(target.id(),"RESOURCE_LIMIT_VALIDATIONS");return false;}
                var built=planner.build(geometry,RoadShape.length(path));if(built==null){rejected(target.id(),planner.failureReason());continue;}
                var reason=network.validate(built.columns(),built.supports(),pair.source.access.endpoint(),pair.source.y,settings.maximumColumns(),work);
                if(reason!=null){failures.put(target.id(),reason);continue;}
                return commit(target,nodes,pair,built.points().stream().map(p->new Vec2(p.x(),p.z())).toList(),built.points(),built.columns(),built.supports(),RoadPlan.Kind.BOARDWALK);
            }
        } finally {operations+=planner.operations();}
        failures.putIfAbsent(target.id(),planner.failureReason());return false;
    }
    private boolean commit(RoadPlan.Node target,List<RoadPlan.Node> nodes,Pair pair,List<Vec2> path,List<RoadPlan.Point3> geometry,
                           List<RoadPlan.Column> delta,List<RoadPlan.Support> supports,RoadPlan.Kind kind) {
        boolean junction=nodes.stream().noneMatch(n->n.id().equals(pair.source.id));
        boolean newTarget=nodes.stream().noneMatch(n->n.id().equals(target.id()));
        if(nodes.size()+(junction?1:0)+(newTarget?1:0)>settings.maximumNodes()){failures.put(target.id(),"RESOURCE_LIMIT_NODES");return false;}
        var end=pair.target.endpoint();
        var endpoint=delta.stream().filter(c->c.x()==(int)Math.floor(end.x())&&c.z()==(int)Math.floor(end.z())).findFirst().orElse(null);
        if(endpoint==null){failures.put(target.id(),"ENDPOINT_MISSING");return false;}
        if(junction) {
            var p=pair.source.access.endpoint();nodes.add(new RoadPlan.Node(pair.source.id,(int)Math.floor(p.x()),(int)Math.floor(p.z()),false,Integer.MIN_VALUE,RoadPlan.NodeKind.JUNCTION));
            travel.put(pair.source.id,pair.source.distance);
        }
        if(newTarget)nodes.add(new RoadPlan.Node(target.id(),endpoint.x(),endpoint.z(),target.required(),kind==RoadPlan.Kind.GROUND?Integer.MIN_VALUE:endpoint.deckY(),target.kind()));
        lockedAccess.add(target.id());
        double length=RoadShape.length(path);travel.put(target.id(),pair.source.distance+length);
        network.commit(delta,supports);network.route(path,pair.source.distance,work);
        routes.add(new RoadPlan.Route(pair.source.id+"->"+target.id(),pair.source.id,target.id(),path,length,geometry,kind));
        return true;
    }
    private void addLoops(List<RoadPlan.Node> nodes,RoadSearch search) {
        if(settings.loopBudgetFraction()==0||routes.isEmpty())return;
        double remaining=routes.stream().mapToDouble(RoadPlan.Route::length).sum()*settings.loopBudgetFraction();
        var destinations=nodes.stream().filter(n->n.kind()==RoadPlan.NodeKind.DESTINATION).toList();
        // At most one local shortcut attempt per destination; no new global route search.
        for(var target:destinations) {
            if(target.id().equals("spawn"))continue;
            work.task("loop/"+target.id());
            try {
                var source=destinations.stream().filter(n->!n.id().equals(target.id()))
                        .filter(n->routes.stream().noneMatch(r->r.from().equals(n.id())&&r.to().equals(target.id())||r.to().equals(n.id())&&r.from().equals(target.id())))
                        .min(Comparator.comparingDouble((RoadPlan.Node n)->distance(n,target)).thenComparing(RoadPlan.Node::id)).orElse(null);
                if(source==null)continue;
                var from=accessPoints(source).getFirst();var to=accessPoints(target).getFirst();double length=RoadShape.distance(from.endpoint(),to.endpoint());
                if(length>remaining)continue;
                double previous=network.distance(from.endpoint(),source.y(),to.endpoint(),target.y(),work);
                if(!worthwhileLoop(previous,length))continue;
                var cell=network.at(from.endpoint(),source.y());if(cell==null)continue;
                var pair=new Pair(new Source(source.id(),from,cell.deckY(),travel.get(source.id())),to,length);
                var raw=dense(List.of(from.approach(),to.approach()));
                if(raw.size()<2)continue;
                var path=withConnectors(RoadShape.shape(raw,seed,source.id()+"/"+target.id(),settings,search::dryLegal),from,to);
                double shapedLength=RoadShape.length(path);
                if(shapedLength>remaining||!worthwhileLoop(previous,shapedLength))continue;
                // Repairs also obey the remaining construction and travel-benefit budgets.
                double maximumLength=Math.min(remaining,Math.min(Math.nextDown(previous/1.5),previous-PlanningPolicy.CURRENT.minimumLoopSaving()));
                if(work.candidate(shapedLength)&&Double.isFinite(search.estimate(path))
                        &&ground(target,nodes,pair,path,search,true,maximumLength))remaining-=RoadShape.length(routes.getLast().points());
            } catch(RoadWorkBudget.Limit ignored) { /* Optional improvements never consume required work. */ }
        }
    }
    static boolean worthwhileLoop(double previous,double added) {
        var policy=PlanningPolicy.CURRENT;
        return Double.isFinite(previous)&&previous>added*1.5
                &&previous-added>=policy.minimumLoopSaving()&&previous+added>=policy.minimumLoopPerimeter();
    }
    private boolean validPoint(int x,int z) {
        if(Math.abs(x)>radius||Math.abs(z)>radius)return false;
        for(var r:reservations)if(r.bounds().contains(x+.5,z+.5,settings.width()/2.+1))return false;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {var s=sample(x+dx+.5,z+dz+.5);if(s.wet()||s.hazardous())return false;}
        return true;
    }
    private void add(List<RoadPlan.Node> nodes,RoadPlan.Node node) {
        if(!validPoint(node.x(),node.z())){missing(node.id(),node.required(),"INVALID_ENDPOINT");return;}
        // Coincident destinations can share the root/endpoint; retain an explicit diagnostic.
        if(nodes.stream().anyMatch(n->n.x()==node.x()&&n.z()==node.z())) {skipped.add(new RoadPlan.Skipped(node.id(),"COINCIDENT_ENDPOINT"));return;}
        if(nodes.size()>=settings.maximumNodes()) {
            if(!node.required()) {skipped.add(new RoadPlan.Skipped(node.id(),"OPTIONAL_NODE_BUDGET"));return;}
            var optional=nodes.stream().filter(n->!n.required()).reduce((a,b)->b);
            if(optional.isPresent()) {
                nodes.remove(optional.get());accessCandidates.remove(optional.get().id());
                skipped.add(new RoadPlan.Skipped(optional.get().id(),"OPTIONAL_NODE_BUDGET"));
            } else work.capacity(RoadWorkControl.Kind.NODES,nodes.size(),settings.maximumNodes());
        }
        nodes.add(node);
    }
    private void missing(String id,boolean required,String reason) {
        if(required)throw failure(reason.startsWith("RESOURCE_LIMIT")?PlanningFailure.Code.RESOURCE_LIMIT:PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,
                "required road destination cannot be connected","node",id+":"+reason);
        skipped.add(new RoadPlan.Skipped(id,reason));
    }
    private static List<Vec2> withConnectors(List<Vec2> macro,Access from,Access to) {
        var result=new ArrayList<Vec2>();
        if(!from.endpoint().equals(from.approach()))result.add(from.endpoint());
        result.addAll(macro);
        if(!to.endpoint().equals(to.approach()))result.add(to.endpoint());
        return List.copyOf(result);
    }

    private List<Access> accessPoints(RoadPlan.Node node) {
        var values=accessCandidates.getOrDefault(node.id(),List.of(Access.point(point(node))));
        return lockedAccess.contains(node.id())?values.stream().filter(a->a.endpoint().equals(point(node))).toList():values;
    }
    private static Vec2 point(RoadPlan.Node node){return new Vec2(node.x()+.5,node.z()+.5);}
    private static double distance(RoadPlan.Node a,RoadPlan.Node b){return RoadShape.distance(point(a),point(b));}
    private static PlanningFailure failure(PlanningFailure.Code code,String message,String key,Object value){return new PlanningFailure(code,FailureStage.ROADS,message,Map.of(key,value));}
}
