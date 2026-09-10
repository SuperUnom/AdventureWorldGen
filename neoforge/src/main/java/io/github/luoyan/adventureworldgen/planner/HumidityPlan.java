package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.HumidityType;
import io.github.luoyan.adventureworldgen.noise.ValueNoise;
import java.util.*;
import java.util.function.DoubleConsumer;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;

/** Continuous moisture over frozen terrain; water proximity is prepared before any biome grows. */
public final class HumidityPlan {
    public static final int STEP=32;
    private static final double REACH=512;
    private final AdventureWorldConfig config;
    private final ClimatePlan temperature;
    private final ValueNoise regional,detail,shore;
    private final int extent,width;
    private final double weatherOffset;
    private final double[] freshDistance,oceanDistance,freshLevel,oceanLevel;
    private final double[] actual=new double[3];
    private final FrozenQuartField frozenValues;
    private record SupplyKey(String category,String recipe,String secondary,String landform) {}
    private final Map<SupplyKey,Integer> moistureSupply=new java.util.concurrent.ConcurrentHashMap<>();

    public record State(int extent,double[] freshDistance,double[] oceanDistance,
                        double[] freshLevel,double[] oceanLevel,double[] actual,double weatherOffset) {}
    public State snapshot() {
        return new State(extent,freshDistance.clone(),oceanDistance.clone(),freshLevel.clone(),oceanLevel.clone(),actual.clone(),weatherOffset);
    }
    public HumidityPlan(long seed,AdventureWorldConfig config,MacroTerrain terrain,ClimatePlan temperature,
                        DoubleConsumer progress,State frozen) {
        this.config=config;this.temperature=temperature;
        double radius=config.world().radius();
        frozenValues=new FrozenQuartField(radius+128);
        regional=new ValueNoise(seed,"humidity/region",Math.max(160,radius*.35));
        detail=new ValueNoise(seed,"humidity/detail",Math.max(96,radius*.1));
        shore=new ValueNoise(seed,"humidity/shore",112);
        // Include water beyond the playable circle, so the outer coast receives the same moisture.
        extent=(int)Math.ceil(radius/STEP)+2;width=extent*2+1;
        long size=(long)width*width;
        if(size>PlannerProfile.V2.maximumCostNodes())throw new PlanningFailure(
                PlanningFailure.Code.RESOURCE_LIMIT,"humidity","environment grid exceeds budget");
        if(frozen!=null) {
            if(frozen.extent()!=extent)throw new IllegalArgumentException("invalid frozen humidity extent");
            if(!Double.isFinite(frozen.weatherOffset())||Math.abs(frozen.weatherOffset())>.49)
                throw new IllegalArgumentException("invalid frozen humidity weather offset");
            weatherOffset=frozen.weatherOffset();
            freshDistance=restore(frozen.freshDistance(),(int)size,true);
            oceanDistance=restore(frozen.oceanDistance(),(int)size,true);
            freshLevel=restore(frozen.freshLevel(),(int)size,false);
            oceanLevel=restore(frozen.oceanLevel(),(int)size,false);
            double[] ratios=restore(frozen.actual(),3,false);
            for(double ratio:ratios)if(ratio<0||ratio>1)throw new IllegalArgumentException("invalid humidity ratio");
            if(Math.abs(Arrays.stream(ratios).sum()-1)>1e-6)throw new IllegalArgumentException("invalid humidity ratios");
            System.arraycopy(ratios,0,actual,0,3);
            return;
        }
        freshDistance=new double[(int)size];oceanDistance=new double[(int)size];
        freshLevel=new double[(int)size];oceanLevel=new double[(int)size];
        Arrays.fill(freshDistance,REACH);Arrays.fill(oceanDistance,REACH);
        double weatherSum=0;int weatherCount=0;
        for(int gz=0;gz<width;gz++) {
            for(int gx=0;gx<width;gx++) {
                int i=gz*width+gx,x=(gx-extent)*STEP+2,z=(gz-extent)*STEP+2;
                var center=terrain.sample(x,z);
                source(i,0,center);
                if(Math.hypot(x,z)<=radius&&!center.wet()&&!center.hazardous()){weatherSum+=weather(x,z);weatherCount++;}
                // Subsamples catch channels narrower than the environmental grid.
                if(center.waterKind()!=WaterKind.OCEAN)for(int dz=-12;dz<=12;dz+=8)for(int dx=-12;dx<=12;dx+=8)
                    source(i,Math.hypot(dx,dz),terrain.sample(x+dx,z+dz));
            }
            progress.accept(.75*(gz+1)/width);
        }
        weatherOffset=weatherCount==0?0:weatherSum/weatherCount;
        spread(freshDistance,freshLevel);spread(oceanDistance,oceanLevel);
        int count=0;
        for(int gz=0;gz<width;gz++)for(int gx=0;gx<width;gx++) {
            int x=(gx-extent)*STEP+2,z=(gz-extent)*STEP+2;
            if(Math.hypot(x,z)>radius)continue;
            var s=terrain.sample(x,z);
            if(s.wet()||s.hazardous())continue;
            actual[typeAt(x,z,s).ordinal()]++;count++;
        }
        if(count==0)throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"humidity","no dry land");
        for(int i=0;i<actual.length;i++)actual[i]/=count;
        progress.accept(1);
    }
    private static double[] restore(double[] values,int size,boolean distance) {
        if(values==null||values.length!=size)throw new IllegalArgumentException("invalid frozen humidity dimensions");
        for(double v:values)if(!Double.isFinite(v)||(distance&&(v<0||v>REACH)))
            throw new IllegalArgumentException("invalid frozen humidity field");
        return values.clone();
    }
    private void source(int i,double distance,MacroSample s) {
        boolean ocean=s.waterKind()==WaterKind.OCEAN;
        if(!ocean&&s.waterKind()!=WaterKind.RIVER&&s.waterKind()!=WaterKind.LAKE&&s.waterKind()!=WaterKind.WETLAND)return;
        double[] distances=ocean?oceanDistance:freshDistance,levels=ocean?oceanLevel:freshLevel;
        if(distance<distances[i]){distances[i]=distance;levels[i]=s.waterSurface();}
    }
    /** Two-pass eight-neighbour distance transform, bounded in memory and work. */
    private void spread(double[] distances,double[] levels) {
        for(int pass=0;pass<2;pass++) {
            int direction=pass==0?1:-1;
            for(int j=0;j<distances.length;j++) {
                int i=pass==0?j:distances.length-1-j,x=i%width,z=i/width;
                relax(distances,levels,i,x-direction,z,STEP);
                for(int dx=-1;dx<=1;dx++)relax(distances,levels,i,x+dx,z-direction,STEP*(dx==0?1:Math.sqrt(2)));
            }
        }
    }
    private void relax(double[] distances,double[] levels,int i,int x,int z,double step) {
        if(x<0||z<0||x>=width||z>=width)return;
        int n=z*width+x;
        double candidate=distances[n]+step;
        if(candidate<distances[i]){distances[i]=candidate;levels[i]=levels[n];}
    }
    private double sample(double[] field,double x,double z) {
        double gx=Math.clamp((x-2)/STEP+extent,0,width-1.000001),gz=Math.clamp((z-2)/STEP+extent,0,width-1.000001);
        int ix=(int)gx,iz=(int)gz;double tx=gx-ix,tz=gz-iz;
        tx=tx*tx*(3-2*tx);tz=tz*tz*(3-2*tz);
        int i=iz*width+ix;
        return (field[i]*(1-tx)+field[i+1]*tx)*(1-tz)+(field[i+width]*(1-tx)+field[i+width+1]*tx)*tz;
    }
    public double freshDistanceAt(double x,double z){return sample(freshDistance,x,z);}
    public double oceanDistanceAt(double x,double z){return sample(oceanDistance,x,z);}
    private double weather(double x,double z){return .36*regional.sample(x,z)+.13*detail.sample(x,z);}
    /** 0..1 moisture: broad weather, evaporation, elevation and nearby fresh/salt water. */
    public double valueAt(double x,double z,MacroSample s) {
        return frozenValues.get(x,z,()->computeValue(x,z,s));
    }
    private double computeValue(double x,double z,MacroSample s) {
        double ocean=oceanDistanceAt(x,z);
        double maritime=s.waterKind()==WaterKind.OCEAN?1:Math.exp(-ocean/200);
        double value=.44+weather(x,z)-weatherOffset
                -.20*(temperature.valueAt(x,z,s)/10-.5)-.12*Math.clamp((s.groundSurface()-80)/200,0,1)
                +.24*maritime;
        // Rivers overlay the land biome and do not redraw its climate boundary. This lets a
        // channel pass through an otherwise legal dry biome instead of becoming a wet stripe.
        value=Math.max(value,.64*Math.clamp(1-ocean/96,0,1));
        value=Math.clamp(value,0,1);
        if(s.wet())return value;
        // Plan moisture inside the feasible template/landform domain before assigning biomes.
        // This never changes a biome's allowed humidity set, nor the final terrain or water geometry.
        var key=new SupplyKey(s.terrainTemplate(),s.recipe(),s.secondaryWeight()>0?s.secondaryRecipe():"",s.landform());
        int mask=moistureSupply.computeIfAbsent(key,ignored->{
            int result=0;
            for(var id:config.biomes().filler()) {
                var rule=config.biomes().terrainRules().get(id);
                if(rule!=null&&(rule.shoreOnly()||rule.minHeight()!=null||rule.maxHeight()!=null||!rule.accepts(s)))continue;
                if(rule==null||rule.humidities().isEmpty())result=7;
                else for(var type:rule.humidities().keySet())result|=1<<type.ordinal();
            }
            return result;
        });
        int type=value<.38?0:value<.68?1:2;
        if(mask==0||(mask&(1<<type))!=0)return value;
        double best=value,distance=Double.POSITIVE_INFINITY;
        for(int i=0;i<3;i++)if((mask&(1<<i))!=0) {
            double candidate=Math.clamp(value,i==0?0:i==1?.380001:.680001,i==0?.379999:i==1?.679999:1);
            if(Math.abs(candidate-value)<distance){best=candidate;distance=Math.abs(candidate-value);}
        }
        return best;
    }
    public HumidityType typeAt(double x,double z,MacroSample s) {
        double value=valueAt(x,z,s);
        return value<.38?HumidityType.DRY:value<.68?HumidityType.MEDIUM:HumidityType.WET;
    }
    public boolean allows(ContentId id,double x,double z,MacroSample s) {
        var rule=config.biomes().terrainRules().get(id);
        return rule==null||rule.humidities().isEmpty()||rule.humidities().containsKey(typeAt(x,z,s));
    }
    public double cost(ContentId id,double x,double z,MacroSample s) {
        var rule=config.biomes().terrainRules().get(id);
        if(rule==null||rule.humidities().isEmpty())return 0;
        var prefs=rule.humidities();double value=valueAt(x,z,s),best=Double.POSITIVE_INFINITY;
        double max=prefs.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        for(var e:prefs.entrySet()) {
            double center=switch(e.getKey()){case DRY->.19;case MEDIUM->.53;case WET->.84;};
            double deviation=Math.max(0,Math.abs(value-center)-.12);
            best=Math.min(best,deviation*deviation*8-Math.log(e.getValue()/max)*.3);
        }
        return best;
    }
    /** Reserve intermittent low ocean banks only; inland rivers never create beach biomes. */
    public boolean isShore(double x,double z,MacroSample s) {
        if(s.wet()||s.hazardous()||s.terrainTemplate().equals("mountains")||shore.sample(x,z)<0)return false;
        double distance=oceanDistanceAt(x,z);
        if(distance>36)return false;
        double level=sample(oceanLevel,x,z),rise=s.groundSurface()-level;
        return rise>=0&&rise<=7;
    }
    public double[] actualRatios(){return actual.clone();}
}
