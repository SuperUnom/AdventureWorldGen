package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.terrain.ValueNoise;
import java.util.*;
import java.util.function.DoubleConsumer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Connected competitive growth. Memory contains ownership and active frontiers, never all biome/cell edges. */
public final class BiomeAllocationPlanner {
    private static final int[][] DIR={{4,0},{-4,0},{0,4},{0,-4}};
    private static final long BUDGET=12_000_000;
    private record Ranked(PlacementIndex.Point point,ContentId biome,int band,double score) {}
    private static final Comparator<Ranked> SEED_ORDER=Comparator.comparingInt(Ranked::band)
            .thenComparingDouble(Ranked::score).thenComparingLong(v->v.point.cell()).thenComparing(Ranked::biome);
    private record Edge(long cell,double path,int band,double score) {}
    public record Result(List<PlannedBiomePatch> patches,long operations) {}
    private final Map<ContentId,it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap> environments=new HashMap<>();
    private long operations;
    private AdventureWorldConfig config;
    private PlacementIndex index;
    private ClimatePlan climate;
    private List<PlannedBiomePatch> reservations;
    private final Long2IntOpenHashMap owner=new Long2IntOpenHashMap();
    private final List<Region> regions=new ArrayList<>();
    private DoubleConsumer progress;
    private int requiredCount;
    private static final class Region {
        final RequirementExpander.PatchDemand demand;
        ContentId biome;
        final ValueNoise noise;
        final LongOpenHashSet cells=new LongOpenHashSet(),queued=new LongOpenHashSet();
        final PriorityQueue<Edge> frontier=new PriorityQueue<>(Comparator.comparingInt(Edge::band).thenComparingDouble(Edge::score).thenComparingLong(Edge::cell));
        PlacementIndex.Point anchor;
        int retries;
        boolean filler;
        Region(long seed,RequirementExpander.PatchDemand d) {
            demand=d;biome=d.allowedBiomes().getFirst();noise=new ValueNoise(seed,"growth/"+d.patchId(),192);
        }
        long minimum(){return demand.area().inCells(4).min();}
        long target(){return Math.max(minimum(),demand.area().target()/16);}
        long maximum(){return demand.area().inCells(4).max();}
    }
    public Result allocate(long seed,AdventureWorldConfig config,PlacementIndex index,
                           List<RequirementExpander.PatchDemand> demands,List<PlannedBiomePatch> reservations) {
        return allocate(seed,config,index,demands,reservations,new ClimatePlan(seed,config,index::sampleAt),ignored->{});
    }
    public Result allocate(long seed,AdventureWorldConfig config,PlacementIndex index,
                           List<RequirementExpander.PatchDemand> demands,List<PlannedBiomePatch> reservations,
                           ClimatePlan climate,DoubleConsumer progress) {
        this.config=config;this.index=index;this.reservations=reservations;this.climate=climate;this.progress=progress;
        operations=0;environments.clear();owner.clear();owner.defaultReturnValue(-1);regions.clear();
        String spawn=demands.stream().filter(d->config.spawn().hasBiome()&&d.adventureLevel()==0&&d.allowedBiomes().contains(config.spawn().biome()))
                .map(RequirementExpander.PatchDemand::patchId).findFirst().orElse(null);
        if(!config.spawn().hasBiome()&&config.spawn().hasStructure())
            spawn=StableIds.carrierPatch(StableIds.structureInstance(config.spawn().structure().id(),0));
        final String spawnId=spawn;
        String spawnCarrier=config.spawn().hasStructure()?StableIds.carrierPatch(StableIds.structureInstance(config.spawn().structure().id(),0)):null;
        var ordered=new ArrayList<>(demands);
        ordered.sort(Comparator.comparingInt((RequirementExpander.PatchDemand d)->d.patchId().equals(spawnId)?-2:d.patchId().equals(spawnCarrier)?-1:0)
                .thenComparingLong(d->index.terrainCapacity(d.allowedBiomes()))
                .thenComparing(Comparator.comparingLong((RequirementExpander.PatchDemand d)->d.area().target()).reversed())
                .thenComparingInt(RequirementExpander.PatchDemand::adventureLevel)
                .thenComparing(RequirementExpander.PatchDemand::patchId));
        for(var demand:ordered) {
            var region=new Region(seed,demand);regions.add(region);
            region.anchor=selectSeed(seed,region,demand.patchId().equals(spawn));
            claimSeedCore(regions.size()-1);
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
        // Only failed regions are reset. Other successful ownership and all hard rules remain fixed.
        for(int pass=0;pass<3;pass++) {
            boolean deficient=false;
            for(int i=0;i<regions.size();i++) {
                var r=regions.get(i);if(r.cells.size()>=r.minimum())continue;
                deficient=true;
                if(r.demand.patchId().equals(spawn))continue;
                r.retries++;
                io.github.luoyan.adventureworldgen.runtime.PlanningProgress.detailCurrent("修复 "+r.biome+"：合法连通空间不足，重新选址 "+r.retries+"/3");
                for(long cell:r.cells)owner.remove(cell);
                r.cells.clear();r.queued.clear();r.frontier.clear();
                r.anchor=selectSeed(seed,r,false);
                claimSeedCore(i);
            }
            if(!deficient)break;
            growTo(false);
        }
        List<String> missing=new ArrayList<>();long deficit=0;
        for(var r:regions)if(r.cells.size()<r.minimum()) {
            var rule=config.biomes().terrainRules().get(r.biome);
            missing.add(r.demand.patchId()+" "+r.biome+" terrain="+(rule==null?"all":rule.allowedTerrain())
                    +" missing_area="+((r.minimum()-r.cells.size())*16));deficit+=r.minimum()-r.cells.size();
        }
        if(!missing.isEmpty())throw new PlanningFailure(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED,"connected-capacity",
                "shared dry legal land could not satisfy connected minimum quotas after local retries",
                Map.of("affected",missing,"missing_area",deficit*16,"operations",operations,"seed",seed));
        seedFillers(seed);
        growTo(true);
        progress.accept(.98);
        var patches=new ArrayList<PlannedBiomePatch>();
        for(var r:regions) {
            if(r.filler&&r.cells.size()<256)continue; // final filler pass absorbs undersized seed remnants
            int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
            for(long c:r.cells){int x=CellMask.x(c),z=CellMask.z(c);minX=Math.min(minX,x);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x+4);maxZ=Math.max(maxZ,z+4);}
            patches.add(new PlannedBiomePatch(r.demand.patchId(),r.biome,r.demand.adventureLevel(),minX,minZ,maxX,maxZ,
                    new CellMask(r.cells),r.anchor.x(),r.anchor.z()));
        }
        progress.accept(1);return new Result(List.copyOf(patches),operations);
    }
    private PlacementIndex.Point selectSeed(long seed,Region r,boolean spawn) {
        if(spawn&&config.spawn().hasBiome()) {
            var p=new PlacementIndex.Point(0,0);
            if(legal(r,p.x(),p.z())&&!owner.containsKey(p.cell()))return p;
            throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"spawn-core","spawn cell is not legal dry land",Map.of("biome",r.biome));
        }
        boolean central=spawn || config.spawn().hasStructure()&&r.demand.patchId().equals(
                StableIds.carrierPatch(StableIds.structureInstance(config.spawn().structure().id(),0)));
        double scale=Math.sqrt(r.demand.area().target()/Math.PI);
        long salt=DeterministicRandom.seed(seed,PlannerProfile.V2.algorithmVersion(),"biome-seed",r.demand.patchId(),r.retries);
        // Ownership does not change during selection. Reuse exhausted components across
        // candidates, temperature relaxation and grid refinement, then discard the snapshot.
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
                for(var other:regions)if(other!=r&&other.anchor!=null) {
                    double desired=(scale+Math.sqrt(other.demand.area().target()/Math.PI))*1.1;
                    double overlap=Math.max(0,1-Math.hypot(p.x()-other.anchor.x(),p.z()-other.anchor.z())/desired);
                    crowd+=18*overlap*overlap;
                }
                int clearance=Math.max(16,(int)(scale*.55)/4*4);
                for(int[] d:DIR)if(!legal(r,p.x()+d[0]*clearance/4,p.z()+d[1]*clearance/4))capacity+=2;
                double u=Math.max(1e-12,(PlacementIndex.mix(p.cell()^salt)>>>11)*0x1.0p-53);
                // Temperature wins; deterministic jitter breaks close suitability ties.
                double score=climate.cost(r.biome,p.x()+2,p.z()+2,index.sample(p.x(),p.z()))*3
                        +4*index.penalty(r.demand.adventureLevel(),p)+crowd+capacity+Math.log(-Math.log(u))*.35;
                int band=climate.temperatureDistance(biome,p.x()+2,p.z()+2,index.sample(p.x(),p.z()));
                var candidate=new Ranked(p,biome,band,score);var bucket=ranked.get(band);
                if(bucket.size()<1024)bucket.add(candidate);
                else if(SEED_ORDER.compare(candidate,bucket.peek())<0){bucket.remove();bucket.add(candidate);}
            }
            // Relax temperature, then conservative capacity probes. Final minimum quotas stay fixed.
            var candidates=new ArrayList<Ranked>();
            for(var bucket:ranked)candidates.addAll(bucket);
            candidates.sort(SEED_ORDER);
            for(int relaxation=0;relaxation<2;relaxation++)for(var candidate:candidates) {
                if(!index.accepts(r.demand.adventureLevel(),candidate.point))continue;
                r.biome=candidate.biome;
                var capacity=capacityProbes.computeIfAbsent(r.biome,ignored->new ConnectedCapacityProbe(cell->
                        !owner.containsKey(cell)&&legal(r,CellMask.x(cell),CellMask.z(cell))));
                if(capacity.knownInsufficient(candidate.point.cell(),r.minimum()))continue;
                if(!supportsCarrierCore(r,candidate.point))continue;
                int probe=(int)Math.min(r.minimum(),relaxation==0?4096:256);
                if(capacity.measure(candidate.point.cell(),probe).supports(r.minimum(),probe))return candidate.point;
            }
        }
        throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"biome-seed","no sufficiently connected legal seed with required carrier clearance",
                Map.of("biome",r.biome,"minimum",r.demand.area().min(),"target",r.demand.area().target(),"retry",r.retries,"competing_biomes",regions.stream().filter(other->!other.filler).map(other->other.biome.toString()).distinct().toList()));
    }
    /** Reserve ownership, never reshape terrain or prepare a structure before biome growth finishes. */
    private static int carrierCoreSide(Region r) {
        if(!r.demand.patchId().startsWith("patch/carrier/"))return 0;
        return Math.max(1,Math.min(16,(int)Math.sqrt(r.minimum())));
    }
    private boolean supportsCarrierCore(Region r,PlacementIndex.Point p) {
        int side=carrierCoreSide(r);if(side==0)return true;
        if(!JointPlanner.sufficientlyFlat(index::sampleAt,p.x(),p.z()))return false;
        for(int dz=0;dz<side;dz++)for(int dx=0;dx<side;dx++) {
            int x=p.x()+(dx-side/2)*4,z=p.z()+(dz-side/2)*4;
            if(owner.containsKey(CellMask.key(x,z))||!legal(r,x,z))return false;
        }
        return true;
    }
    private void claimSeedCore(int i) {
        var r=regions.get(i);int side=carrierCoreSide(r);
        claim(i,r.anchor.cell(),0);
        // A bounded compact interior gives small carriers usable width. Their outer frontier
        // still competes and grows organically under the same area and environment constraints.
        for(int dz=0;dz<side;dz++)for(int dx=0;dx<side;dx++) {
            int x=r.anchor.x()+(dx-side/2)*4,z=r.anchor.z()+(dz-side/2)*4;
            long cell=CellMask.key(x,z);
            if(!r.cells.contains(cell))claim(i,cell,Math.hypot(x-r.anchor.x(),z-r.anchor.z()));
        }
    }
    /** Introduce filler competitors after the minimum round; retain their claimed cells in the frozen plan. */
    private void seedFillers(long seed) {
        if(regions.stream().allMatch(r->r.cells.size()>=r.maximum()))return;
        var points=new ArrayList<>(index.candidates(0));
        points.sort(Comparator.comparingLong(p->PlacementIndex.mix(seed^p.cell())));
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
            double spacing=320+192*((PlacementIndex.mix(point.cell()^seed^123)>>>11)*0x1.0p-53);
            boolean close=false;
            for(var r:regions) {
                double separation=r.filler?spacing:Math.sqrt(r.demand.area().target()/Math.PI)*2.0;
                if(Math.hypot(point.x()-r.anchor.x(),point.z()-r.anchor.z())<separation){close=true;break;}
            }
            if(close)continue;
            ContentId best=null;int bestBand=4;double score=Double.POSITIVE_INFINITY;
            for(var biome:config.biomes().filler()) {
                if(!index.allows(biome,point.x(),point.z())||!climate.allowsEnvironment(biome,point.x(),point.z(),index.sample(point.x(),point.z())))continue;
                int band=climate.temperatureDistance(biome,point.x()+2,point.z()+2,index.sample(point.x(),point.z()));
                if(band>bestBand)continue;
                double density=0;
                for(var r:regions)if(r.biome.equals(biome))density+=Math.exp(-Math.pow(Math.hypot(point.x()-r.anchor.x(),point.z()-r.anchor.z())/384,2));
                var rule=config.biomes().terrainRules().get(biome);
                double level=rule!=null&&rule.adventureLevel()!=null?rule.adventureLevel():config.biomes().required().stream()
                        .filter(r->r.id().equals(biome)).mapToInt(AdventureWorldConfig.RequiredBiome::adventureLevel).average().orElse(5);
                double u=Math.max(1e-12,(PlacementIndex.mix(seed^point.cell()^biome.hashCode())>>>11)*0x1.0p-53);
                double cost=climate.cost(biome,point.x()+2,point.z()+2,index.sample(point.x(),point.z()))*6+density*.15
                        +Math.pow((level-10*Math.hypot(point.x(),point.z())/config.world().radius())/5,2)
                        +Math.log(-Math.log(u))-Math.log(ClimatePlan.weight(config,biome));
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
        for(int[] d:DIR) {
            int nx=x+d[0],nz=z+d[1];long n=CellMask.key(nx,nz);
            if(owner.containsKey(n)||r.queued.contains(n)||!legal(r,nx,nz))continue;
            r.queued.add(n);var sample=index.sample(nx,nz);
            double environment=climate.cost(r.biome,nx+2,nz+2,sample);
            int band=climate.temperatureDistance(r.biome,nx+2,nz+2,sample);
            double next=path+4;
            int support=0;for(int[] side:DIR)if(owner.get(CellMask.key(nx+side[0],nz+side[1]))==i)support++;
            double cost=next*.65+environment*128+r.noise.sample(nx,nz)*24-support*9;
            r.frontier.add(new Edge(n,next,band,cost));
        }
    }
    private boolean legal(Region r,int x,int z) {
        // Terrain, configuration, climate and reservations are immutable for this allocation.
        // Charge actual eligibility work once per biome/cell, not every frontier/probe cache hit.
        var cache=environments.computeIfAbsent(r.biome,ignored->new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap());
        long cell=CellMask.key(x,z);byte allowed=cache.get(cell);
        if(allowed!=0)return allowed==1;
        if(++operations>BUDGET)throw new PlanningFailure(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED,"competitive-growth",
                "unique biome/cell eligibility budget exhausted",Map.of("biome",r.biome,"operations",operations,"budget",BUDGET));
        boolean valid=Math.hypot(x,z)<=config.world().radius();
        if(valid)for(var p:reservations)if(p.contains(x,z)){valid=false;break;}
        if(valid)valid=index.allows(r.biome,x,z)
                &&climate.allowsEnvironment(r.biome,x,z,index.sample(x,z));
        cache.put(cell,(byte)(valid?1:2));
        return valid;
    }
}
