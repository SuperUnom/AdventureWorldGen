package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.terrain.ValueNoise;
import java.util.*;

/** Frozen variable-spacing seeds and multi-source frontier growth over remaining land. */
public final class FillerLayout {
    private static final int STEP=16;
    private final int extent,width;
    private final int[] labels;
    private final MacroSample[] environment;
    private final AdventureWorldConfig config;
    private final ClimatePlan climate;
    private final ValueNoise warpX,warpZ,shape;
    private final List<ContentId> pool;
    private final List<Seed> seeds=new ArrayList<>();
    private final PriorityQueue<Edge> frontier=new PriorityQueue<>(Comparator.comparingDouble(Edge::cost)
            .thenComparingInt(Edge::seed).thenComparingInt(Edge::cell));
    private record Seed(int cell,int biome) {}
    private record Edge(int cell,int seed,double cost) {}
    private final long worldSeed;
    private final double[] levels;
    private int visited,assigned,total;
    private int restoredSeedCount=-1;
    public record State(int extent,int[] labels,int seedCount) {}
    public State snapshot(){return new State(extent,labels.clone(),seedCount());}
    private final java.util.function.DoubleConsumer progress=io.github.luoyan.adventureworldgen.runtime.PlanningProgress.withinCurrent(io.github.luoyan.adventureworldgen.runtime.PlanningProgress.Stage.FILLER);
    public FillerLayout(long seed,AdventureWorldConfig config,MacroTerrain terrain,List<PlannedBiomePatch> patches,ClimatePlan climate) {
        this(seed,config,terrain,patches,climate,null);
    }
    public FillerLayout(long seed,AdventureWorldConfig config,MacroTerrain terrain,List<PlannedBiomePatch> patches,ClimatePlan climate,State frozen) {
        this.worldSeed=seed;this.config=config;this.climate=climate;pool=config.biomes().filler();
        warpX=new ValueNoise(seed,"filler/boundary-x",80);warpZ=new ValueNoise(seed,"filler/boundary-z",80);
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
                frontier.add(new Edge(n,number,STEP));
        }
        // Seed positions have a strict minimum distance and variable separation, independent of grid rows.
        List<Integer> candidates=new ArrayList<>();
        for(int i=0;i<labels.length;i++)if(environment[i]!=null&&labels[i]==-1)candidates.add(i);
        total=candidates.size();
        candidates.sort(Comparator.comparingLong(i->PlacementIndex.mix(seed^i)));
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
        frontier.add(new Edge(cell,number,0));
    }
    private boolean allows(ContentId id,int cell) {
        var sample=environment[cell];int x=x(cell),z=z(cell);
        if(!allows(id,x,z,sample))return false;
        if(!VanillaAltitudeSnow.applies(id))return true;
        int y=(int)Math.ceil(sample.groundSurface())+1;
        boolean snow=VanillaAltitudeSnow.snowy(id,x+2,y,z+2),stable=true;
        // A coarse ownership cell should not straddle the native snow line. Keep a modest
        // elevation margin and probe its corners; exact quart queries still enforce real weather.
        for(int dx:new int[]{-8,8})for(int dz:new int[]{-8,8})
            if(VanillaAltitudeSnow.snowy(id,x+2+dx,y+(snow?-12:12),z+2+dz)!=snow)stable=false;
        if(stable)return true;
        // Prefer a stable species over a noisy ring of per-quart replacements. A profile with
        // only altitude-sensitive species retains its legal candidates instead of becoming invalid.
        return pool.stream().noneMatch(other->!VanillaAltitudeSnow.applies(other)&&allows(other,x,z,sample));
    }
    private boolean allows(ContentId id,int x,int z,MacroSample sample) {
        return config.biomes().allows(id,sample)&&climate.allowsEnvironment(id,x,z,sample);
    }
    private int choose(int cell) {
        int best=-1;double score=Double.POSITIVE_INFINITY;
        for(int b=0;b<pool.size();b++) {
            ContentId id=pool.get(b);if(!allows(id,cell))continue;
            double density=0;
            for(var s:seeds)if(s.biome==b)density+=Math.exp(-Math.pow(Math.hypot(x(cell)-x(s.cell),z(cell)-z(s.cell))/384,2));
            double u=Math.max(1e-12,(PlacementIndex.mix(worldSeed^((long)cell<<16)^b)>>>11)*0x1.0p-53);
            double value=climate.cost(id,x(cell),z(cell),environment[cell])*7+density*.12
                    +adventure(b,cell)+Math.log(-Math.log(u))-Math.log(ClimatePlan.weight(config,id));
            if(value<score){score=value;best=b;}
        }
        if(best<0)throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"filler","no legal filler",
                Map.of("x",x(cell),"z",z(cell),"terrain",environment[cell].terrainTemplate(),"recipe",environment[cell].recipe(),"secondary",environment[cell].secondaryRecipe(),"landform",environment[cell].landform(),"humidity",climate.humidity().typeAt(x(cell),z(cell),environment[cell]),"temperature",climate.typeAt(x(cell),z(cell),environment[cell])));
        return best;
    }
    private double adventure(int biome,int cell) {
        return Double.isFinite(levels[biome])?Math.pow((levels[biome]-10*Math.hypot(x(cell),z(cell))/config.world().radius())/4,2):0;
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
                double step=STEP*(1+climate.cost(id,x(n),z(n),environment[n])*.3+adventure(seed.biome,n)*.02)
                        +Math.abs(environment[i].groundSurface()-environment[n].groundSurface())*.5
                        +4*(1+shape.sample(x(n),z(n)));
                frontier.add(new Edge(n,edge.seed,edge.cost+step));
            }
        }
    }
    private int[] neighbors(int i){return new int[]{i%width>0?i-1:-1,i%width<width-1?i+1:-1,i>=width?i-width:-1,i+width<labels.length?i+width:-1};}
    private void tidy() {
        // Two strictly local passes remove one-cell holes and spikes; hard rules and reservations win.
        for(int pass=0;pass<2;pass++) {
            int[] next=labels.clone();
            for(int i=0;i<labels.length;i++)if(labels[i]>=0) {
                int own=0;Map<Integer,Integer> support=new TreeMap<>();
                for(int n:neighbors(i))if(n>=0&&labels[n]>=0){support.merge(labels[n],1,Integer::sum);if(labels[n]==labels[i])own++;}
                if(own>1)continue;
                for(var e:support.entrySet())if(e.getValue()>=3&&allows(pool.get(e.getKey()),i)){next[i]=e.getKey();break;}
            }
            System.arraycopy(next,0,labels,0,labels.length);
        }
    }
    public ContentId biomeAt(int x,int z,MacroSample sample) {
        int gx=(int)Math.floor((x+7*warpX.sample(x,z))/STEP)+extent;
        int gz=(int)Math.floor((z+7*warpZ.sample(x,z))/STEP)+extent;
        int best=-1;double score=Double.POSITIVE_INFINITY;
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
            int nx=gx+dx,nz=gz+dz;if(nx<0||nz<0||nx>=width||nz>=width)continue;
            int i=nz*width+nx,b=labels[i];if(b<0||!allows(pool.get(b),x,z,sample))continue;
            double d=Math.hypot(x-x(i)-7*warpX.sample(x,z),z-z(i)-7*warpZ.sample(x,z));
            if(d<score){score=d;best=b;}
        }
        if(best<0)for(int b=0;b<pool.size();b++)if(allows(pool.get(b),x,z,sample)) {
            double cost=climate.cost(pool.get(b),x,z,sample)-Math.log(ClimatePlan.weight(config,pool.get(b)))*.1;
            if(cost<score){score=cost;best=b;}
        }
        if(best<0)throw new IllegalStateException("No legal filler at "+x+","+z);
        return pool.get(best);
    }
    public int seedCount(){return restoredSeedCount>=0?restoredSeedCount:seeds.size();}
    private int x(int i){return (i%width-extent)*STEP;}
    private int z(int i){return (i/width-extent)*STEP;}
}
