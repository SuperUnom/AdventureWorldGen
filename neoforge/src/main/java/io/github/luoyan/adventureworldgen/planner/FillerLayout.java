package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.plan.PlanningObserver;
import io.github.luoyan.adventureworldgen.plan.PlanningStage;
import io.github.luoyan.adventureworldgen.noise.ValueNoise;
import java.util.*;
import io.github.luoyan.adventureworldgen.spatial.CellMask;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.noise.DeterministicRandom;

/** Frozen variable-spacing seeds and multi-source frontier growth over remaining land. */
public final class FillerLayout {
    private static final int STEP=16;
    private final int extent,width;
    private final int[] labels;
    private final MacroSample[] environment;
    private final AdventureWorldConfig config;
    private final BiomeEnvironmentRules rules;
    private final ValueNoise shape;
    private final io.github.luoyan.adventureworldgen.noise.ContinuousDomainWarp boundaryWarp;
    private final List<ContentId> pool;
    private final List<Seed> seeds=new ArrayList<>();
    private final PriorityQueue<Edge> frontier=new PriorityQueue<>(Comparator.comparingInt(Edge::band).thenComparingDouble(Edge::cost)
            .thenComparingInt(Edge::seed).thenComparingInt(Edge::cell));
    private record Seed(int cell,int biome) {}
    private record Edge(int cell,int seed,int band,double cost) {}
    private final long worldSeed;
    private final double[] levels;
    private int visited,assigned,total;
    private int restoredSeedCount=-1;
    public record State(int extent,int[] labels,int seedCount) {}
    public State snapshot(){return new State(extent,labels.clone(),seedCount());}
    private final java.util.function.DoubleConsumer progress;
    public FillerLayout(long seed,AdventureWorldConfig config,MacroTerrain terrain,List<PlannedBiomePatch> patches,BiomeEnvironmentRules rules) {
        this(seed,config,terrain,patches,rules,null);
    }
    public FillerLayout(long seed,AdventureWorldConfig config,MacroTerrain terrain,List<PlannedBiomePatch> patches,BiomeEnvironmentRules rules,State frozen) {
        this(seed,config,terrain,patches,rules,PlanningObserver.NONE,frozen);
    }
    public FillerLayout(long seed,AdventureWorldConfig config,MacroTerrain terrain,List<PlannedBiomePatch> patches,BiomeEnvironmentRules rules,
                        PlanningObserver observer,State frozen) {
        this.progress=observer.within(PlanningStage.FILLER);
        this.worldSeed=seed;this.config=config;this.rules=rules;pool=config.biomes().filler();
        boundaryWarp=new io.github.luoyan.adventureworldgen.noise.ContinuousDomainWarp(seed,"filler/boundary",1);
        shape=new ValueNoise(seed,"filler/frontier",128);
        extent=(int)Math.ceil(config.world().radius()/STEP)+1;width=extent*2+1;
        long size=(long)width*width;
        if(size>PlannerProfile.V2.maximumCostNodes())throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT,"filler","grid exceeds budget",Map.of("cells",size));
        if(frozen!=null) {
            if(frozen.extent()!=extent || frozen.labels().length!=size || frozen.seedCount()<0)
                throw new IllegalArgumentException("invalid frozen filler dimensions");
            for(int label:frozen.labels())if(label< -2||label>=pool.size())throw new IllegalArgumentException("invalid frozen filler label");
            labels=frozen.labels().clone();restoredSeedCount=frozen.seedCount();
            environment=new MacroSample[0];levels=new double[0];return;
        }
        labels=new int[(int)size];Arrays.fill(labels,-1);environment=new MacroSample[labels.length];
        levels=new double[pool.size()];
        for(int b=0;b<pool.size();b++) {
            ContentId biomeId=pool.get(b);
            var rule=config.biomes().terrainRules().get(biomeId);
            levels[b]=rule!=null&&rule.adventureLevel()!=null?rule.adventureLevel():config.biomes().required().stream()
                    .filter(r->r.id().equals(biomeId)).mapToInt(AdventureWorldConfig.RequiredBiome::adventureLevel).average().orElse(Double.NaN);
        }
        for(int i=0;i<labels.length;i++) {
            if(i%width==0)progress.accept(.2*i/labels.length);
            int x=x(i),z=z(i);
            if(Math.hypot(x,z)>config.world().radius())continue;
            var sample=terrain.sample(x+2,z+2);
            if(sample.waterKind()==WaterKind.OCEAN||sample.hazardous())continue;
            environment[i]=sample;
            // Required areas act as occupied obstacles during filler growth.
            for(var p:patches)if(p.contains(x+2,z+2)) {
                labels[i]=p.patchId().startsWith("filler/")?pool.indexOf(p.biomeId()):-2;
                break;
            }
        }
        // Continue the filler frontiers that already competed with the required target round.
        Map<Integer,Integer> existingSeeds=new HashMap<>();
        for(int i=0;i<labels.length;i++)if(labels[i]>=0) {
            boolean boundary=false;
            for(int n:neighbors(i))if(n>=0&&environment[n]!=null&&labels[n]==-1){boundary=true;break;}
            if(!boundary)continue;
            Integer number=existingSeeds.get(labels[i]);
            if(number==null){number=seeds.size();existingSeeds.put(labels[i],number);seeds.add(new Seed(i,labels[i]));}
            for(int n:neighbors(i))if(n>=0&&environment[n]!=null&&labels[n]==-1)
                enqueue(n,number,stepDistance(i,n));
        }
        // Seed positions have a strict minimum distance and variable separation, independent of grid rows.
        List<Integer> candidates=new ArrayList<>();
        for(int i=0;i<labels.length;i++)if(environment[i]!=null&&labels[i]==-1)candidates.add(i);
        total=candidates.size();
        candidates.sort(Comparator.comparingLong(i->DeterministicRandom.mix(seed^i)));
        Map<Long,List<Integer>> buckets=new HashMap<>();
        for(int i:candidates) {
            int bx=Math.floorDiv(x(i),768),bz=Math.floorDiv(z(i),768);
            double spacing=384+256*(.5+.5*shape.sample(x(i),z(i)));
            boolean close=false;
            for(int dx=-1;dx<=1&&!close;dx++)for(int dz=-1;dz<=1&&!close;dz++)
                for(int j:buckets.getOrDefault(CellMask.key((bx+dx)*4,(bz+dz)*4),List.of()))
                    if(Math.hypot(x(i)-x(j),z(i)-z(j))<spacing){close=true;break;}
            if(close)continue;
            addSeed(i);buckets.computeIfAbsent(CellMask.key(bx*4,bz*4),ignored->new ArrayList<>()).add(i);
        }
        progress.accept(.4);
        flood();
        // A hard terrain barrier can isolate empty space. Seed only those still unassigned components.
        for(int i:candidates)if(labels[i]==-1){addSeed(i);flood();}
        tidy();
        progress.accept(1);
    }
    private void addSeed(int cell) {
        int biome=choose(cell);int number=seeds.size();seeds.add(new Seed(cell,biome));
        enqueue(cell,number,0);
    }
    private boolean allows(ContentId id,int cell) {
        return allows(id,x(cell),z(cell),environment[cell]);
    }
    private boolean allows(ContentId id,int x,int z,MacroSample sample) {
        return config.biomes().allows(id,sample)&&rules.allows(id,x,z,sample);
    }
    private int choose(int cell) {
        int best=-1,bestBand=4;double score=Double.POSITIVE_INFINITY;
        for(int b=0;b<pool.size();b++) {
            ContentId id=pool.get(b);if(!allows(id,cell))continue;
            int band=rules.temperatureDistance(id,x(cell)+2,z(cell)+2,environment[cell]);
            if(band>bestBand)continue;
            double density=0;
            for(var s:seeds)if(s.biome==b)density+=Math.exp(-Math.pow(Math.hypot(x(cell)-x(s.cell),z(cell)-z(s.cell))/384,2));
            double u=Math.max(1e-12,(DeterministicRandom.mix(worldSeed^((long)cell<<16)^b)>>>11)*0x1.0p-53);
            double value=rules.cost(id,x(cell)+2,z(cell)+2,environment[cell])*7+density*.12
                    +adventure(b,cell)+Math.log(-Math.log(u))-Math.log(BiomeEnvironmentRules.weight(config,id));
            if(band<bestBand||value<score){score=value;best=b;bestBand=band;}
        }
        if(best<0)throw noLegalFiller(x(cell),z(cell),environment[cell]);
        return best;
    }
    private double adventure(int biome,int cell) {
        return Double.isFinite(levels[biome])?Math.pow((levels[biome]-10*Math.hypot(x(cell),z(cell))/config.world().radius())/4,2):0;
    }
    private void enqueue(int cell,int seed,double cost) {
        int band=rules.temperatureDistance(pool.get(seeds.get(seed).biome),x(cell)+2,z(cell)+2,environment[cell]);
        frontier.add(new Edge(cell,seed,band,cost));
    }
    private void flood() {
        while(!frontier.isEmpty()) {
            Edge edge=frontier.remove();int i=edge.cell;
            if(++visited>labels.length*32L)throw new PlanningFailure(PlanningFailure.Code.SEARCH_BUDGET_EXHAUSTED,"filler","frontier budget exhausted");
            if(labels[i]!=-1)continue;
            Seed seed=seeds.get(edge.seed);ContentId id=pool.get(seed.biome);
            if(!allows(id,i))continue;
            labels[i]=seed.biome;assigned++;
            if((assigned&511)==0)progress.accept(.4+.55*assigned/Math.max(1.0,total));
            for(int n:neighbors(i))if(n>=0&&environment[n]!=null&&labels[n]==-1&&allows(id,n)) {
                double distance=stepDistance(i,n);
                double step=distance*(1+rules.cost(id,x(n)+2,z(n)+2,environment[n])*.3+adventure(seed.biome,n)*.02)
                        +STEP*rules.temperatureDistance(id,x(n)+2,z(n)+2,environment[n])*8
                        +4*(1+shape.sample(x(n),z(n)));
                enqueue(n,edge.seed,edge.cost+step);
            }
        }
    }
    private int[] neighbors(int i) {
        int gx=i%width,gz=i/width;int[] result=new int[8];int p=0;
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)if(dx!=0||dz!=0)
            result[p++]=gx+dx>=0&&gx+dx<width&&gz+dz>=0&&gz+dz<width?(gz+dz)*width+gx+dx:-1;
        return result;
    }
    private double stepDistance(int from,int to) {
        return from%width==to%width||from/width==to/width?STEP:STEP*StrictMath.sqrt(2);
    }
    private void tidy() {
        // Two strictly local passes remove one-cell holes and spikes; hard rules and reservations win.
        for(int pass=0;pass<2;pass++) {
            int[] next=labels.clone();
            for(int i=0;i<labels.length;i++)if(labels[i]>=0) {
                int own=0;Map<Integer,Integer> support=new TreeMap<>();
                for(int n:neighbors(i))if(n>=0&&labels[n]>=0){support.merge(labels[n],1,Integer::sum);if(labels[n]==labels[i])own++;}
                if(own>3)continue;
                for(var e:support.entrySet())if(e.getValue()>=5&&allows(pool.get(e.getKey()),i)
                        &&rules.temperatureDistance(pool.get(e.getKey()),x(i)+2,z(i)+2,environment[i])
                        <=rules.temperatureDistance(pool.get(labels[i]),x(i)+2,z(i)+2,environment[i])){next[i]=e.getKey();break;}
            }
            System.arraycopy(next,0,labels,0,labels.length);
        }
    }
    public ContentId biomeAt(int x,int z,MacroSample sample) {
        // Reconstruct categorical coverage with a C2 cubic B-spline. Nearest-cell
        // Voronoi lookup exposes the planning grid as polygonal corners even after warping.
        var point=boundaryWarp.apply(x,z);
        double px=point.x()/STEP,pz=point.z()/STEP;
        int gx=(int)Math.floor(px),gz=(int)Math.floor(pz);
        double[] support=new double[pool.size()];
        for(int dz=-1;dz<=2;dz++)for(int dx=-1;dx<=2;dx++) {
            int nx=gx+dx+extent,nz=gz+dz+extent;
            if(nx<0||nz<0||nx>=width||nz>=width)continue;
            int b=labels[nz*width+nx];
            if(b>=0)support[b]+=spline(px-gx-dx)*spline(pz-gz-dz);
        }
        int best=-1,bestBand=4;double score=-1;
        for(int b=0;b<pool.size();b++) {
            if(support[b]<=0||!allows(pool.get(b),x,z,sample))continue;
            int band=rules.temperatureDistance(pool.get(b),Math.floor(x/4.0)*4+2,Math.floor(z/4.0)*4+2,sample);
            if(band<bestBand||(band==bestBand&&support[b]>score)){score=support[b];best=b;bestBand=band;}
        }
        if(bestBand>0) {
            double fallbackScore=Double.POSITIVE_INFINITY;
            int localBand=bestBand;
            for(int b=0;b<pool.size();b++)if(allows(pool.get(b),x,z,sample)) {
                int band=rules.temperatureDistance(pool.get(b),Math.floor(x/4.0)*4+2,Math.floor(z/4.0)*4+2,sample);
                if(band>bestBand||(best>=0&&band>=localBand))continue;
                double cost=rules.cost(pool.get(b),x,z,sample)-Math.log(BiomeEnvironmentRules.weight(config,pool.get(b)))*.1;
                if(band<bestBand||cost<fallbackScore){fallbackScore=cost;best=b;bestBand=band;}
            }
        }
        if(best<0)throw noLegalFiller(x,z,sample);
        return pool.get(best);
    }
    private static double spline(double distance) {
        double t=Math.abs(distance);
        return t<1 ? (4-6*t*t+3*t*t*t)/6 : t<2 ? Math.pow(2-t,3)/6 : 0;
    }
    private PlanningFailure noLegalFiller(int x,int z,MacroSample sample) {
        double qx=Math.floor(x/4.0)*4+2,qz=Math.floor(z/4.0)*4+2;
        return new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"filler",
                "no filler satisfies biomes.terrain_rules; add coverage for this terrain, temperature and humidity",
                Map.of("x",x,"z",z,"terrain",sample.terrainTemplate(),"recipe",sample.recipe(),
                        "secondary",sample.secondaryRecipe(),"landform",sample.landform(),
                        "humidity",rules.humidity().typeAt(qx,qz,sample),"temperature",rules.temperature().typeAt(qx,qz,sample)));
    }
    public int seedCount(){return restoredSeedCount>=0?restoredSeedCount:seeds.size();}
    private int x(int i){return (i%width-extent)*STEP;}
    private int z(int i){return (i/width-extent)*STEP;}
}
