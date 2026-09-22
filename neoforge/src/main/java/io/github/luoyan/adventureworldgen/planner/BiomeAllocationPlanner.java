package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.plan.PlanningObserver;
import io.github.luoyan.adventureworldgen.noise.ValueNoise;
import java.util.*;
import java.util.function.DoubleConsumer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import io.github.luoyan.adventureworldgen.spatial.CellMask;
import io.github.luoyan.adventureworldgen.noise.DeterministicRandom;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.plan.StableIds;
import io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules;
import io.github.luoyan.adventureworldgen.biome.OrganicGrowth;
import io.github.luoyan.adventureworldgen.climate.ClimatePlan;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/** Competitive growth with bounded multi-region recovery; environmental admission never relaxes. */
public final class BiomeAllocationPlanner {
    private static final int[][] DIR={{4,0},{-4,0},{0,4},{0,-4}};
    private final PlannerProfile profile;

    /**
     * @param profile supplies both the algorithm-version salt of the deterministic biome seeds and
     *                the competitive-growth operation budget. Neither is read from a fixed V2
     *                reference any more, so an injected profile really governs this stage.
     */
    public BiomeAllocationPlanner(PlannerProfile profile) { this.profile = profile; }
    private static final int MAX_REGION_SEEDS=64;
    private record Ranked(PlacementIndex.Point point,ContentId biome,int band,double score) {}
    private static final Comparator<Ranked> SEED_ORDER=Comparator.comparingInt(Ranked::band)
            .thenComparingDouble(Ranked::score).thenComparingLong(v->v.point.cell()).thenComparing(Ranked::biome);
    private record Edge(long cell,double path,int band,double score) {}
    public record Result(List<PlannedBiomePatch> patches,long operations) {}
    private final Map<ContentId,io.github.luoyan.adventureworldgen.spatial.TiledBitField> environments=new HashMap<>();
    private long operations;
    private AdventureWorldConfig config;
    private PlacementIndex index;
    private BiomeEnvironmentRules rules;
    private List<PlannedBiomePatch> reservations;
    private final Long2IntOpenHashMap owner=new Long2IntOpenHashMap();
    private final List<Region> regions=new ArrayList<>();
    private DoubleConsumer progress;
    private PlanningObserver observer=PlanningObserver.NONE;
    private int requiredCount;
    private static final class Region {
        final RequirementExpander.PatchDemand demand;
        ContentId biome;
        final ValueNoise noise;
        final long shapeSeed;
        final List<OrganicGrowth> catchments=new ArrayList<>();
        final LongOpenHashSet cells=new LongOpenHashSet(),queued=new LongOpenHashSet();
        final PriorityQueue<Edge> frontier=new PriorityQueue<>(Comparator.comparingInt(Edge::band).thenComparingDouble(Edge::score).thenComparingLong(Edge::cell));
        PlacementIndex.Point anchor;
        int retries;
        int seedCount=1,supplementStep;
        Iterator<PlacementIndex.Point> supplements=Collections.emptyIterator();
        boolean filler;
        Region(long seed,RequirementExpander.PatchDemand d) {
            shapeSeed=seed;demand=d;biome=d.allowedBiomes().getFirst();noise=new ValueNoise(seed,"growth/"+d.patchId(),192);
        }
        long minimum(){return demand.area().inCells(4).min();}
        long target(){return Math.max(minimum(),demand.area().target()/16);}
        long maximum(){return demand.area().inCells(4).max();}
    }
    public Result allocate(long seed,AdventureWorldConfig config,PlacementIndex index,
                           List<RequirementExpander.PatchDemand> demands,List<PlannedBiomePatch> reservations) {
        return allocate(seed,config,index,demands,reservations,new BiomeEnvironmentRules(config,new ClimatePlan(seed,config,index::sampleAt,
                new ClimateDiagnostics(config,ClimatePlan.STEP))),PlanningObserver.NONE,ignored->{});
    }
    public Result allocate(long seed,AdventureWorldConfig config,PlacementIndex index,
                           List<RequirementExpander.PatchDemand> demands,List<PlannedBiomePatch> reservations,
                           BiomeEnvironmentRules rules,DoubleConsumer progress) {
        return allocate(seed,config,index,demands,reservations,rules,PlanningObserver.NONE,progress);
    }
    public Result allocate(long seed,AdventureWorldConfig config,PlacementIndex index,
                           List<RequirementExpander.PatchDemand> demands,List<PlannedBiomePatch> reservations,
                           BiomeEnvironmentRules rules,PlanningObserver observer,DoubleConsumer progress) {
        this.config=config;this.index=index;this.reservations=reservations;this.rules=rules;this.progress=progress;
        this.observer=observer;
        operations=0;environments.clear();owner.clear();owner.defaultReturnValue(-1);regions.clear();
        String spawn=demands.stream().filter(RequirementExpander.PatchDemand::spawn)
                .map(RequirementExpander.PatchDemand::patchId).findFirst().orElse(null);
        final String spawnId=spawn;
        var ordered=new ArrayList<>(demands);
        ordered.sort(Comparator.comparingInt((RequirementExpander.PatchDemand d)->d.patchId().equals(spawnId)?-2:d.requiresSeed()?-1:0)
                .thenComparingLong(d->index.terrainCapacity(d.allowedBiomes()))
                .thenComparing(Comparator.comparingLong((RequirementExpander.PatchDemand d)->d.area().target()).reversed())
                .thenComparingInt(RequirementExpander.PatchDemand::adventureLevel)
                .thenComparing(RequirementExpander.PatchDemand::patchId));
        var sources=new RequirementExpander().expandUnmerged(config).patches();
        for(int demandIndex=0;demandIndex<ordered.size();demandIndex++) {
            var demand=ordered.get(demandIndex);
            var region=new Region(seed,demand);regions.add(region);
            try {region.anchor=selectSeed(seed,region,demand.spawn());}
            catch(PlanningFailure failure) {
                if(failure.code()!=PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN||demand.members().size()<2)throw failure;
                // Keep every earlier claim. Only the failed shared carrier is expanded back to
                // its original source roles; a source may use alternatives lost by intersection.
                regions.removeLast();ordered.remove(demandIndex);
                var memberIds=demand.members().stream().map(RequirementExpander.Member::patchId).collect(java.util.stream.Collectors.toSet());
                var separated=sources.stream().filter(source->memberIds.contains(source.patchId()))
                        .sorted(Comparator.comparing((RequirementExpander.PatchDemand d)->!d.spawn())
                                .thenComparing(d->!d.requiresSeed()).thenComparing(RequirementExpander.PatchDemand::patchId)).toList();
                ordered.addAll(demandIndex,separated);demandIndex--;
                observer.detail("拆分共享承载组："+demand.patchId()+"，保留先前区域");continue;
            }
            if(region.anchor==null) {
                System.getLogger(BiomeAllocationPlanner.class.getName()).log(System.Logger.Level.WARNING,
                        "Biome area relaxed: {0} {1}, requested minimum={2}, actual=0; no legal seed",
                        demand.patchId(),region.biome,demand.area().min());
                regions.removeLast();
                continue;
            }
            claim(regions.size()-1,region.anchor.cell(),0);
            index.supply.reserve(region.biome,region.anchor.x(),region.anchor.z(),region.demand.area().min());
            progress.accept(.18*regions.size()/Math.max(1,ordered.size()));
        }
        // Stable connected spawn core before any competitor can surround it.
        if(spawn!=null) {
            Region r=regions.getFirst();
            double clearance=regions.stream().skip(1).mapToDouble(other->Math.hypot(other.anchor.x()-r.anchor.x(),other.anchor.z()-r.anchor.z())/3).min().orElse(32);
            int limit=(int)Math.min(r.minimum(),Math.PI*Math.pow(Math.min(32,clearance),2)/16);
            while(r.cells.size()<limit&&grow(0)){}
        }
        requiredCount=regions.size();
        growTo(false);
        // Keep every successful claim. If a frontier exhausts below minimum, seed another
        // legal component. Each region scans the candidate catalogs only once across retries.
        for(int round=1;round<MAX_REGION_SEEDS;round++) {
            boolean added=false;
            for(int i=0;i<requiredCount;i++) {
                var r=regions.get(i);if(r.cells.size()>=r.minimum())continue;
                var point=nextSupplement(r);if(point==null)continue;
                claim(i,point.cell(),0);r.seedCount++;added=true;
                observer.detail(
                        "补充区域 "+r.biome+"："+r.seedCount+" 个种子，保留温湿度与地形限制");
            }
            if(!added)break;
            growTo(false);
        }
        seedFillers(seed);
        growTo(true);
        progress.accept(.98);
        var patches=new ArrayList<PlannedBiomePatch>();
        for(var r:regions) {
            if(!r.filler&&(r.seedCount>1||r.cells.size()<r.minimum()))
                System.getLogger(BiomeAllocationPlanner.class.getName()).log(System.Logger.Level.WARNING,
                        "Biome area relaxed: {0} {1}, requested minimum={2}, actual={3}, region seeds={4}",
                        r.demand.patchId(),r.biome,r.demand.area().min(),r.cells.size()*16L,r.seedCount);
            if(r.filler&&r.cells.size()<256)continue; // final filler pass absorbs undersized seed remnants
            int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
            for(long c:r.cells){int x=CellMask.x(c),z=CellMask.z(c);minX=Math.min(minX,x);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x+4);maxZ=Math.max(maxZ,z+4);}
            patches.add(new PlannedBiomePatch(r.demand.patchId(),r.biome,r.demand.adventureLevel(),minX,minZ,maxX,maxZ,
                    new CellMask(r.cells),r.anchor.x(),r.anchor.z()));
        }
        progress.accept(1);return new Result(List.copyOf(patches),operations);
    }
    private PlacementIndex.Point selectSeed(long seed,Region r,boolean spawn) {
        if(spawn) {
            var p=new PlacementIndex.Point(0,0);
            if(legal(r,p.x(),p.z())&&!owner.containsKey(p.cell()))return p;
            throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, FailureStage.SPAWN_CORE,
                    "spawn cell violates configured terrain/climate or land constraints",
                    Map.of("biome",r.biome,"temperature",rules.temperature().typeAt(2,2,index.sample(0,0)),
                            "allowed_temperatures",BiomeEnvironmentRules.preferences(config,r.biome).keySet(),
                            "humidity",rules.humidity().typeAt(2,2,index.sample(0,0))));
        }
        boolean central=spawn;
        double scale=Math.sqrt(r.demand.area().target()/Math.PI);
        long salt=DeterministicRandom.seed(seed,profile.algorithmVersion(),"biome-seed",r.demand.patchId(),r.retries);
        // Ownership does not change during selection. Reuse exhausted components across
        // candidates, capacity probe refinement and grid refinement, then discard the snapshot.
        Map<ContentId,ConnectedCapacityProbe> capacityProbes=new HashMap<>();
        for(int step:new int[]{16,8,4}) {
            // Bounded greedy shortlist per temperature distance; no full catalog sort.
            List<PriorityQueue<Ranked>> ranked=new ArrayList<>();
            for(int band=0;band<4;band++)ranked.add(new PriorityQueue<>(SEED_ORDER.reversed()));
            for(var biome:r.demand.allowedBiomes())for(var p:index.candidates(r.demand.adventureLevel(),step)) {
                r.biome=biome;
                var explored=capacityProbes.get(biome);
                if(explored!=null&&explored.knownInsufficient(p.cell(),r.minimum()))continue;
                if(central&&Math.hypot(p.x(),p.z())>Math.min(256,config.world().radius()/10)*.8)continue;
                if(owner.containsKey(p.cell())||!index.accepts(r.demand.adventureLevel(),p)||!legal(r,p.x(),p.z()))continue;
                double crowd=0,capacity=0;
                long available=index.supply.available(biome,p.x(),p.z());
                if(available>=0)capacity+=8*Math.max(0,1-available/(double)Math.max(16,r.demand.area().min()));
                for(var other:regions)if(other!=r&&other.anchor!=null) {
                    double desired=(scale+Math.sqrt(other.demand.area().target()/Math.PI))*1.1;
                    double overlap=Math.max(0,1-Math.hypot(p.x()-other.anchor.x(),p.z()-other.anchor.z())/desired);
                    crowd+=18*overlap*overlap;
                }
                int clearance=Math.max(16,(int)(scale*.55)/4*4);
                for(int[] d:DIR)if(!legal(r,p.x()+d[0]*clearance/4,p.z()+d[1]*clearance/4))capacity+=2;
                double u=Math.max(1e-12,(DeterministicRandom.mix(p.cell()^salt)>>>11)*0x1.0p-53);
                // Temperature wins; deterministic jitter breaks close suitability ties.
                double score=rules.cost(r.biome,p.x()+2,p.z()+2,index.sample(p.x(),p.z()))*3
                        +4*index.penalty(r.demand.adventureLevel(),p)+crowd+capacity+Math.log(-Math.log(u))*.35;
                int band=rules.temperatureDistance(biome,p.x()+2,p.z()+2,index.sample(p.x(),p.z()));
                var candidate=new Ranked(p,biome,band,score);var bucket=ranked.get(band);
                if(bucket.size()<1024)bucket.add(candidate);
                else if(SEED_ORDER.compare(candidate,bucket.peek())<0){bucket.remove();bucket.add(candidate);}
            }
            // Prefer a connected minimum within this bounded shortlist. If it cannot fit,
            // retain the largest measured legal component and recover with additional seeds.
            Ranked fallback=null;int fallbackCells=-1;
            var candidates=new ArrayList<Ranked>();
            for(var bucket:ranked)candidates.addAll(bucket);
            candidates.sort(SEED_ORDER);
            for(int relaxation=0;relaxation<2;relaxation++)for(var candidate:candidates) {
                if(!index.accepts(r.demand.adventureLevel(),candidate.point))continue;
                r.biome=candidate.biome;
                var capacity=capacityProbes.computeIfAbsent(r.biome,ignored->new ConnectedCapacityProbe(cell->
                        !owner.containsKey(cell)&&legal(r,CellMask.x(cell),CellMask.z(cell))));
                if(capacity.knownInsufficient(candidate.point.cell(),r.minimum()))continue;
                int probe=(int)Math.min(r.minimum(),relaxation==0?4096:256);
                var measured=capacity.measure(candidate.point.cell(),probe);
                if(measured.supports(r.minimum(),probe))return candidate.point;
                if(measured.cells()>fallbackCells){fallback=candidate;fallbackCells=measured.cells();}
            }
            if(fallback!=null){r.biome=fallback.biome;return fallback.point;}
        }
        // A missing ordinary biome is reported as zero supply. Spawn and required-structure
        // carriers still need one legal ownership seed, but the carrier does not reserve a core or
        // imply any structure footprint around that seed.
        if(!r.demand.requiresSeed())return null;
        String role=central?"spawn":"required structure carrier";
        throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, FailureStage.BIOME_SEED,"no legal biome seed for "+role,
                Map.of("role",role,"patch_id",r.demand.patchId(),"biome",r.biome,"minimum",r.demand.area().min(),"target",r.demand.area().target(),"retry",r.retries,"competing_biomes",regions.stream().filter(other->!other.filler).map(other->other.biome.toString()).distinct().toList()));
    }
    private PlacementIndex.Point nextSupplement(Region r) {
        int[] steps={16,8,4};
        while(true) {
            while(r.supplements.hasNext()) {
                var p=r.supplements.next();
                if(!owner.containsKey(p.cell())&&legal(r,p.x(),p.z())&&index.accepts(r.demand.adventureLevel(),p))return p;
            }
            if(r.supplementStep==steps.length)return null;
            r.supplements=index.candidates(r.demand.adventureLevel(),steps[r.supplementStep++]).iterator();
        }
    }
    /** Introduce filler competitors after the minimum round; retain their claimed cells in the frozen plan. */
    private void seedFillers(long seed) {
        if(regions.stream().allMatch(r->r.cells.size()>=r.maximum()))return;
        var points=new ArrayList<>(index.candidates(0));
        points.sort(Comparator.comparingLong(p->DeterministicRandom.mix(seed^p.cell())));
        for(var point:points) {
            if(owner.containsKey(point.cell()))continue;
            // Distant empty components are seeded by the final filler pass. Here only sites able
            // to contend with a required frontier need to occupy exact quart-grid memory.
            boolean nearby=false;
            for(int i=0;i<requiredCount;i++) {
                var r=regions.get(i);
                if(Math.hypot(point.x()-r.anchor.x(),point.z()-r.anchor.z())<Math.sqrt(r.demand.area().target()/Math.PI)*2.5+128){nearby=true;break;}
            }
            if(!nearby)continue;
            double spacing=320+192*((DeterministicRandom.mix(point.cell()^seed^123)>>>11)*0x1.0p-53);
            boolean close=false;
            for(var r:regions) {
                double separation=r.filler?spacing:Math.sqrt(r.demand.area().target()/Math.PI)*2.0;
                if(Math.hypot(point.x()-r.anchor.x(),point.z()-r.anchor.z())<separation){close=true;break;}
            }
            if(close)continue;
            ContentId best=null;int bestBand=4;double score=Double.POSITIVE_INFINITY;
            for(var biome:config.biomes().filler()) {
                if(!index.allows(biome,point.x(),point.z())||!rules.allows(biome,point.x(),point.z(),index.sample(point.x(),point.z())))continue;
                int band=rules.temperatureDistance(biome,point.x()+2,point.z()+2,index.sample(point.x(),point.z()));
                if(band>bestBand)continue;
                double density=0;
                for(var r:regions)if(r.biome.equals(biome))density+=Math.exp(-Math.pow(Math.hypot(point.x()-r.anchor.x(),point.z()-r.anchor.z())/384,2));
                var rule=config.biomes().terrainRules().get(biome);
                double level=rule!=null&&rule.adventureLevel()!=null?rule.adventureLevel():config.biomes().required().stream()
                        .filter(r->r.id().equals(biome)).mapToInt(AdventureWorldConfig.RequiredBiome::adventureLevel).average().orElse(5);
                double u=Math.max(1e-12,(DeterministicRandom.mix(seed^point.cell()^biome.hashCode())>>>11)*0x1.0p-53);
                double cost=rules.cost(biome,point.x()+2,point.z()+2,index.sample(point.x(),point.z()))*6+density*.15
                        +Math.pow((level-10*Math.hypot(point.x(),point.z())/config.world().radius())/5,2)
                        +Math.log(-Math.log(u))-Math.log(BiomeEnvironmentRules.weight(config,biome));
                if(band<bestBand||cost<score){score=cost;best=biome;bestBand=band;}
            }
            if(best==null)continue;
            var demand=new RequirementExpander.PatchDemand("filler/"+(regions.size()-requiredCount),List.of(best),0,
                    new AdventureWorldConfig.AreaRange(16,Long.MAX_VALUE,524288),"filler",true);
            var r=new Region(seed,demand);r.filler=true;r.anchor=point;
            if(!legal(r,point.x(),point.z()))continue;
            regions.add(r);claim(regions.size()-1,point.cell(),0);
        }
    }

    private record Scheduled(int region,int band,double priority) {}
    private Scheduled schedule(int i,boolean target) {
        var r=regions.get(i);long goal=target?r.maximum():r.minimum();
        if(r.cells.size()>=goal)return null;
        while(!r.frontier.isEmpty()&&owner.containsKey(r.frontier.peek().cell))r.frontier.remove();
        if(r.frontier.isEmpty())return null;
        double fraction=r.cells.size()/(double)(target?r.target():r.minimum());
        double pressure=target?areaPressure(fraction,r.filler):40*(1-fraction);
        return new Scheduled(i,r.frontier.peek().band,r.frontier.peek().score/256.0-pressure);
    }
    /** The target changes willingness continuously; only an explicit maximum stops growth. */
    static double areaPressure(double fraction,boolean filler) {
        return fraction<=1?(filler?1.0:7.0)*(1-fraction):-2.0*Math.log(fraction);
    }
    private void growTo(boolean target) {
        long total=regions.stream().filter(r->!r.filler).mapToLong(r->target?r.target():r.minimum()).sum();
        var queue=new PriorityQueue<Scheduled>(Comparator.comparingInt(Scheduled::band).thenComparingDouble(Scheduled::priority).thenComparingInt(Scheduled::region));
        boolean[] active=new boolean[regions.size()];int requiredActive=0;long processed=0;
        for(int i=0;i<regions.size();i++) {
            var entry=schedule(i,target);if(entry==null)continue;queue.add(entry);active[i]=true;
            if(!regions.get(i).filler)requiredActive++;
        }
        while(!queue.isEmpty()&&requiredActive>0) {
            var old=queue.remove();int i=old.region;var next=schedule(i,target);
            if(next==null) {
                if(active[i]&&!regions.get(i).filler)requiredActive--;active[i]=false;continue;
            }
            // Other claims may have invalidated this frontier's head. Reinsert with its true score.
            if(next.band!=old.band||Double.compare(next.priority,old.priority)!=0){queue.add(next);continue;}
            grow(i);processed++;
            next=schedule(i,target);
            if(next!=null)queue.add(next);
            else {if(!regions.get(i).filler)requiredActive--;active[i]=false;}
            if((processed&1023)==0) {
                long count=regions.stream().filter(r->!r.filler).mapToLong(r->Math.min(r.cells.size(),target?r.target():r.minimum())).sum();
                progress.accept((target?.5:.18)+(target?.47:.32)*count/Math.max(1.0,total));
            }
        }
    }
    private boolean grow(int i) {
        var r=regions.get(i);
        while(!r.frontier.isEmpty()) {
            var e=r.frontier.remove();if(owner.containsKey(e.cell))continue;
            claim(i,e.cell,e.path);return true;
        }
        return false;
    }
    private void claim(int i,long cell,double path) {
        var r=regions.get(i);owner.put(cell,i);r.cells.add(cell);
        int x=CellMask.x(cell),z=CellMask.z(cell);
        if(path==0)r.catchments.add(new OrganicGrowth(r.shapeSeed,r.demand.patchId()+"/"+r.catchments.size(),
                x,z,r.demand.area().target(),index.sample(x,z).groundSurface()));
        for(int[] d:DIR) {
            int nx=x+d[0],nz=z+d[1];long n=CellMask.key(nx,nz);
            if(!owner.containsKey(n)&&!r.queued.contains(n)&&legal(r,nx,nz))queue(r,i,nx,nz,path+4);
            else if(index.sample(nx,nz).waterKind()==io.github.luoyan.adventureworldgen.api.WaterKind.RIVER) {
                // A river is an overlay, not a climate frontier. Let ownership reach the
                // opposite dry bank without counting submerged cells toward biome area.
                for(int step=2;step<=24;step++) {
                    int bx=x+d[0]*step,bz=z+d[1]*step;
                    var water=index.sample(bx,bz).waterKind();
                    if(water==io.github.luoyan.adventureworldgen.api.WaterKind.RIVER)continue;
                    if(water==io.github.luoyan.adventureworldgen.api.WaterKind.NONE) {
                        long bridge=CellMask.key(bx,bz);
                        if(!owner.containsKey(bridge)&&!r.queued.contains(bridge)&&legal(r,bx,bz))
                            queue(r,i,bx,bz,path+4*step);
                    }
                    break;
                }
            }
        }
    }
    private void queue(Region r,int ownerIndex,int x,int z,double next) {
        long cell=CellMask.key(x,z);r.queued.add(cell);var sample=index.sample(x,z);
        double environment=rules.cost(r.biome,x+2,z+2,sample);
        int band=rules.temperatureDistance(r.biome,x+2,z+2,sample);
        int support=0;for(int[] side:DIR)if(owner.get(CellMask.key(x+side[0],z+side[1]))==ownerIndex)support++;
        // Four-neighbour path length is a Manhattan metric: it produces diamonds and
        // straight competition fronts. Connectivity still comes from the frontier, while
        // smooth catchment distance controls its shape (including supplemental regions).
        double distance=Double.POSITIVE_INFINITY;
        for(var catchment:r.catchments)distance=Math.min(distance,catchment.score(x,z,sample.groundSurface()));
        double cost=distance*.65+environment*128+r.noise.sample(x,z)*8-support*3;
        r.frontier.add(new Edge(cell,next,band,cost));
    }
    private boolean legal(Region r,int x,int z) {
        // Terrain, configuration, climate and reservations are immutable for this allocation.
        // Charge actual eligibility work once per biome/cell, not every frontier/probe cache hit.
        var cache=environments.computeIfAbsent(r.biome,ignored->new io.github.luoyan.adventureworldgen.spatial.TiledBitField());
        return cache.get(x,z,()-> {
            io.github.luoyan.adventureworldgen.plan.PlanningExecution.checkCancelled();
            operations++;
            if(operations%Math.max(1,profile.search().competitiveGrowthOperations())==0)
                observer.detail("群系合法域继续计算："+operations);
            boolean valid=Math.hypot(x,z)<=config.world().radius();
            if(valid)for(var p:reservations)if(p.contains(x,z)){valid=false;break;}
            return valid&&(index.hasEnvironment()?index.environmentAllows(r.biome,x,z):index.allows(r.biome,x,z)&&rules.allows(r.biome,x,z,index.sample(x,z)));
        });
    }
}
