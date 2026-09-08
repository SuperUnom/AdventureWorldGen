package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.terrain.ValueNoise;
import java.util.*;
import java.util.function.DoubleConsumer;

/** Connected competitive growth. Memory contains ownership and active frontiers, never all biome/cell edges. */
public final class BiomeAllocationPlanner {
    private static final int[][] DIR={{4,0},{-4,0},{0,4},{0,-4}};
    private static final long BUDGET=8_000_000;
    private record Ranked(PlacementIndex.Point point,double score) {}
    private record Edge(long cell,double path,double score) {}
    public record Result(List<PlannedBiomePatch> patches,long operations) {}
    private long operations;
    private AdventureWorldConfig config;
    private PlacementIndex index;
    private ClimatePlan climate;
    private List<PlannedBiomePatch> reservations;
    private final Map<Long,Integer> owner=new HashMap<>();
    private final List<Region> regions=new ArrayList<>();
    private DoubleConsumer progress;
    private int requiredCount;
    private static final class Region {
        final RequirementExpander.PatchDemand demand;
        final ContentId biome;
        final ValueNoise noise;
        final Set<Long> cells=new HashSet<>(),queued=new HashSet<>();
        final PriorityQueue<Edge> frontier=new PriorityQueue<>(Comparator.comparingDouble(Edge::score).thenComparingLong(Edge::cell));
        PlacementIndex.Point anchor;
        int retries;
        OrganicGrowth shape;
        boolean filler;
        Region(long seed,RequirementExpander.PatchDemand d) {
            demand=d;biome=d.allowedBiomes().getFirst();noise=new ValueNoise(seed,"growth/"+d.patchId(),96);
        }
        long minimum(){return demand.area().inCells(4).min();}
        long target(){return Math.max(minimum(),demand.area().target()/16);}
    }
    public Result allocate(long seed,AdventureWorldConfig config,PlacementIndex index,
                           List<RequirementExpander.PatchDemand> demands,List<PlannedBiomePatch> reservations) {
        return allocate(seed,config,index,demands,reservations,new ClimatePlan(seed,config,index::sampleAt),ignored->{});
    }
    public Result allocate(long seed,AdventureWorldConfig config,PlacementIndex index,
                           List<RequirementExpander.PatchDemand> demands,List<PlannedBiomePatch> reservations,
                           ClimatePlan climate,DoubleConsumer progress) {
        this.config=config;this.index=index;this.reservations=reservations;this.climate=climate;this.progress=progress;
        operations=0;owner.clear();regions.clear();
        String spawn=demands.stream().filter(d->config.spawn().hasBiome()&&d.adventureLevel()==0&&d.allowedBiomes().contains(config.spawn().biome()))
                .map(RequirementExpander.PatchDemand::patchId).findFirst().orElse(null);
        var ordered=new ArrayList<>(demands);
        ordered.sort(Comparator.comparingInt((RequirementExpander.PatchDemand d)->d.patchId().equals(spawn)?-1:d.adventureLevel())
                .thenComparing(Comparator.comparingLong((RequirementExpander.PatchDemand d)->d.area().target()).reversed())
                .thenComparing(RequirementExpander.PatchDemand::patchId));
        for(var demand:ordered) {
            var region=new Region(seed,demand);regions.add(region);
            region.anchor=selectSeed(seed,region,demand.patchId().equals(spawn));
            region.shape=new OrganicGrowth(seed,demand.patchId(),region.anchor.x(),region.anchor.z(),demand.area().target(),index.sample(region.anchor.x(),region.anchor.z()).groundSurface());
            claim(regions.size()-1,region.anchor.cell(),0);
            progress.accept(.18*regions.size()/Math.max(1,ordered.size()));
        }
        // Stable connected spawn core before any competitor can surround it.
        if(spawn!=null) {
            Region r=regions.getFirst();
            double clearance=regions.stream().skip(1).mapToDouble(other->Math.hypot(other.anchor.x(),other.anchor.z())/3).min().orElse(32);
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
                r.shape=new OrganicGrowth(seed,r.demand.patchId(),r.anchor.x(),r.anchor.z(),r.demand.area().target(),index.sample(r.anchor.x(),r.anchor.z()).groundSurface());
                claim(i,r.anchor.cell(),0);
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
        if(spawn) {
            var p=new PlacementIndex.Point(0,0);
            if(legal(r,p.x(),p.z())&&!owner.containsKey(p.cell()))return p;
            throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"spawn-core","spawn cell is not legal dry land",Map.of("biome",r.biome));
        }
        double scale=Math.sqrt(r.demand.area().target()/Math.PI);
        long salt=DeterministicRandom.seed(seed,PlannerProfile.V2.algorithmVersion(),"biome-seed",r.demand.patchId(),r.retries);
        for(int step:new int[]{16,8,4}) {
            var ranked=new ArrayList<Ranked>();
            for(var p:index.candidates(r.demand.adventureLevel(),step)) {
                if(owner.containsKey(p.cell())||!legal(r,p.x(),p.z()))continue;
                double crowd=0,capacity=0;
                for(var other:regions)if(other!=r&&other.anchor!=null) {
                    double desired=(scale+Math.sqrt(other.demand.area().target()/Math.PI))*1.1;
                    double overlap=Math.max(0,1-Math.hypot(p.x()-other.anchor.x(),p.z()-other.anchor.z())/desired);
                    crowd+=18*overlap*overlap;
                }
                int clearance=Math.max(16,(int)(scale*.55)/4*4);
                for(int[] d:DIR)if(!legal(r,p.x()+d[0]*clearance/4,p.z()+d[1]*clearance/4))capacity+=2;
                double u=Math.max(1e-12,(PlacementIndex.mix(p.cell()^salt)>>>11)*0x1.0p-53);
                // Exponential race selects proportionally from high suitability scores.
                double score=climate.cost(r.biome,p.x(),p.z(),index.sample(p.x(),p.z()))*3
                        +4*index.penalty(r.demand.adventureLevel(),p)+crowd+capacity+Math.log(-Math.log(u))*.35;
                ranked.add(new Ranked(p,score));
            }
            ranked.sort(Comparator.comparingDouble(Ranked::score).thenComparingLong(v->v.point.cell()));
            for(var candidate:ranked)if(index.accepts(r.demand.adventureLevel(),candidate.point)) {
                // Probe reachable legal space without counting another region's occupied land twice.
                if(capacity(r,candidate.point,(int)Math.min(r.minimum(),4096))>=Math.min(r.minimum(),4096))return candidate.point;
            }
        }
        throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"biome-seed","no sufficiently connected legal seed",
                Map.of("biome",r.biome,"minimum",r.demand.area().min(),"target",r.demand.area().target(),"retry",r.retries,"competing_biomes",regions.stream().filter(other->!other.filler).map(other->other.biome.toString()).distinct().toList()));
    }
    private int capacity(Region r,PlacementIndex.Point p,int desired) {
        Set<Long> seen=new HashSet<>();ArrayDeque<Long> q=new ArrayDeque<>();seen.add(p.cell());q.add(p.cell());
        while(!q.isEmpty()&&seen.size()<desired) {
            long c=q.remove();int x=CellMask.x(c),z=CellMask.z(c);
            for(int[] d:DIR){int nx=x+d[0],nz=z+d[1];long n=CellMask.key(nx,nz);
                if(!seen.contains(n)&&!owner.containsKey(n)&&legal(r,nx,nz)){seen.add(n);q.add(n);}}
        }
        return seen.size();
    }
    /** Introduce filler competitors after the minimum round; retain their claimed cells in the frozen plan. */
    private void seedFillers(long seed) {
        if(regions.stream().allMatch(r->r.cells.size()>=r.target()))return;
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
            double spacing=160+96*((PlacementIndex.mix(point.cell()^seed^123)>>>11)*0x1.0p-53);
            boolean close=false;
            for(var r:regions) {
                double separation=r.filler?spacing:Math.sqrt(r.demand.area().target()/Math.PI)*.9;
                if(Math.hypot(point.x()-r.anchor.x(),point.z()-r.anchor.z())<separation){close=true;break;}
            }
            if(close)continue;
            ContentId best=null;double score=Double.POSITIVE_INFINITY;
            for(var biome:config.biomes().filler()) {
                if(!index.allows(biome,point.x(),point.z()))continue;
                double density=0;
                for(var r:regions)if(r.biome.equals(biome))density+=Math.exp(-Math.pow(Math.hypot(point.x()-r.anchor.x(),point.z()-r.anchor.z())/384,2));
                var rule=config.biomes().terrainRules().get(biome);
                double level=rule!=null&&rule.adventureLevel()!=null?rule.adventureLevel():config.biomes().required().stream()
                        .filter(r->r.id().equals(biome)).mapToInt(AdventureWorldConfig.RequiredBiome::adventureLevel).average().orElse(5);
                double u=Math.max(1e-12,(PlacementIndex.mix(seed^point.cell()^biome.hashCode())>>>11)*0x1.0p-53);
                double cost=climate.cost(biome,point.x(),point.z(),index.sample(point.x(),point.z()))*3+density
                        +Math.pow((level-10*Math.hypot(point.x(),point.z())/config.world().radius())/5,2)
                        +Math.log(-Math.log(u))-Math.log(ClimatePlan.weight(config,biome));
                if(cost<score){score=cost;best=biome;}
            }
            if(best==null)continue;
            var demand=new RequirementExpander.PatchDemand("filler/"+(regions.size()-requiredCount),List.of(best),0,
                    new AdventureWorldConfig.AreaRange(16,196608,131072),"filler",true);
            var r=new Region(seed,demand);r.filler=true;r.anchor=point;
            r.shape=new OrganicGrowth(seed,demand.patchId(),point.x(),point.z(),demand.area().target(),index.sample(point.x(),point.z()).groundSurface());
            if(!legal(r,point.x(),point.z()))continue;
            regions.add(r);claim(regions.size()-1,point.cell(),0);
        }
    }

    private record Scheduled(int region,double priority) {}
    private Scheduled schedule(int i,boolean target) {
        var r=regions.get(i);long goal=target?r.target():r.minimum();
        if(r.cells.size()>=goal)return null;
        while(!r.frontier.isEmpty()&&owner.containsKey(r.frontier.peek().cell))r.frontier.remove();
        if(r.frontier.isEmpty())return null;
        double fraction=r.cells.size()/(double)goal;
        return new Scheduled(i,r.frontier.peek().score/Math.max(32,Math.sqrt(r.demand.area().target()))
                -(r.filler?1.2:target?6:40)*(1-fraction));
    }
    private void growTo(boolean target) {
        long total=regions.stream().filter(r->!r.filler).mapToLong(r->target?r.target():r.minimum()).sum();
        var queue=new PriorityQueue<Scheduled>(Comparator.comparingDouble(Scheduled::priority).thenComparingInt(Scheduled::region));
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
            if(Double.compare(next.priority,old.priority)!=0){queue.add(next);continue;}
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
        int x=CellMask.x(cell),z=CellMask.z(cell);double height=index.sample(x,z).groundSurface();
        for(int[] d:DIR) {
            int nx=x+d[0],nz=z+d[1];long n=CellMask.key(nx,nz);
            if(owner.containsKey(n)||r.queued.contains(n)||!legal(r,nx,nz))continue;
            r.queued.add(n);var sample=index.sample(nx,nz);
            double next=path+4+Math.abs(height-sample.groundSurface())*.45;
            int support=0;for(int[] side:DIR)if(Objects.equals(owner.get(CellMask.key(nx+side[0],nz+side[1])),i))support++;
            double distance=Math.hypot(nx-r.anchor.x(),nz-r.anchor.z());
            double cost=next*.06+r.shape.score(nx,nz,sample.groundSurface())*.94+climate.cost(r.biome,nx,nz,sample)*12
                    +r.noise.sample(nx,nz)*32-support*5;
            r.frontier.add(new Edge(n,next,cost));
        }
    }
    private boolean legal(Region r,int x,int z) {
        if(++operations>BUDGET)throw new PlanningFailure(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED,"competitive-growth",
                "active frontier/search operation budget exhausted",Map.of("biome",r.biome,"operations",operations,"budget",BUDGET));
        if(Math.hypot(x,z)>config.world().radius())return false;
        for(var p:reservations)if(p.contains(x,z))return false;
        return index.sample(x,z).waterKind()==io.github.luoyan.adventureworldgen.api.WaterKind.NONE&&index.allows(r.biome,x,z);
    }
}
