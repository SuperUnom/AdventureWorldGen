package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.config.AdventureWorldConfig.TemperatureType;
import io.github.luoyan.adventureworldgen.terrain.ValueNoise;
import java.util.*;

/** Demand-calibrated continuous climate, sampled from the same frozen terrain used for ownership. */
public final class ClimatePlan {
    public static final int STEP=32;
    private final AdventureWorldConfig config;
    private final ValueNoise regional, detail, warpX, warpZ, foothills;
    private final int heightExtent,heightWidth;
    private final double[] slopeHeight,regionalHeight;
    private final double radius, core;
    private double angle, cold, hot, low, high;
    private final TemperatureType spawnType;
    private final double[] ratios=new double[3], actual=new double[3];
    private final List<Site> sites=new ArrayList<>();
    private final List<Correction> corrections=new ArrayList<>();
    private record Site(int x,int z,MacroSample sample) {}
    private record Correction(double x,double z,double radius,double delta) {}
    public record Supply(String biome, long target, long legalArea, long climateArea) {}
    private final List<Supply> supply=new ArrayList<>();

    public ClimatePlan(long seed,AdventureWorldConfig config,MacroTerrain terrain) {
        this(seed,config,terrain,ignored->{});
    }
    public ClimatePlan(long seed,AdventureWorldConfig config,MacroTerrain terrain,java.util.function.DoubleConsumer progress) {
        this.config=config;radius=config.world().radius();core=Math.min(64,radius/8);
        regional=new ValueNoise(seed,"climate/region",Math.max(128,radius*.48));
        detail=new ValueNoise(seed,"climate/detail",Math.max(96,radius*.13));
        warpX=new ValueNoise(seed,"climate/warp-x",Math.max(192,radius*.32));
        warpZ=new ValueNoise(seed,"climate/warp-z",Math.max(192,radius*.32));
        foothills=new ValueNoise(seed,"climate/foothills",Math.max(96,radius*.055));
        int extent=(int)Math.ceil(radius/STEP);
        if((2L*extent+1)*(2L*extent+1)>PlannerProfile.V2.maximumCostNodes())
            throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT,"climate","environment grid exceeds budget");
        heightExtent=extent;heightWidth=extent*2+1;
        double[] heights=new double[heightWidth*heightWidth];
        for(int z=-extent;z<=extent;z++) {
            for(int x=-extent;x<=extent;x++) {
                int wx=x*STEP,wz=z*STEP;
                var s=terrain.sample(wx+2,wz+2);
                heights[(z+extent)*heightWidth+x+extent]=s.groundSurface();
                if(Math.hypot(wx,wz)>radius)continue;
                if(s.waterKind()==WaterKind.NONE&&!s.hazardous())sites.add(new Site(wx,wz,s));
            }
            progress.accept(.2*(z+extent+1)/(2*extent+1));
        }
        slopeHeight=blur(heights,heightWidth,3);regionalHeight=blur(heights,heightWidth,8);
        if(sites.isEmpty())throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN,"climate","no allocatable dry land");
        ContentId spawn=config.spawn().biome();
        if(spawn==null&&config.spawn().hasStructure())spawn=config.structures().stream()
                .filter(s->s.id().equals(config.spawn().structure().id())).flatMap(s->s.allowedBiomes().ids().stream()).findFirst().orElse(config.biomes().filler().getFirst());
        spawnType=preferences(config,spawn).entrySet().stream().max(Comparator.<Map.Entry<TemperatureType,Double>>comparingDouble(Map.Entry::getValue)
                .thenComparing(e->-e.getKey().ordinal())).orElseThrow().getKey();
        angle=(PlacementIndex.mix(seed)>>>11)*0x1.0p-53*Math.PI*2;
        double[] required=new double[3],filler=new double[3];
        for(var d:new RequirementExpander().expandMinimum(config).patches()) distribute(d.allowedBiomes().getFirst(),d.area().target(),required);
        for(var s:config.structures())if(s.count().min()>0&&!s.allowedBiomes().ids().isEmpty())
            distribute(s.allowedBiomes().ids().getFirst(),s.allowedBiomes().area().target()*(double)s.count().min(),required);
        for(var id:config.biomes().filler())distribute(id,weight(config,id),filler);
        double rt=Arrays.stream(required).sum(),ft=Arrays.stream(filler).sum();
        for(int i=0;i<3;i++)ratios[i]=.8*(rt>0?required[i]/rt:1.0/3)+.2*(ft>0?filler[i]/ft:1.0/3);
        // Ensure a nonzero middle band between cold and hot, then normalize once.
        for(int i=0;i<3;i++)ratios[i]=Math.max(.04,ratios[i]);
        double sum=Arrays.stream(ratios).sum();for(int i=0;i<3;i++)ratios[i]/=sum;
        double start=(PlacementIndex.mix(seed)>>>11)*0x1.0p-53*Math.PI*2,best=Double.POSITIVE_INFINITY,bestAngle=start;
        for(int trial=0;trial<8;trial++) {
            angle=start+trial*Math.PI/4;calibrate();double cost=supplyCost();
            if(cost<best){best=cost;bestAngle=angle;}
            progress.accept(.2+.6*(trial+1)/8);
        }
        angle=bestAngle;calibrate();
        // Bounded local thermal boundary repair, never an alteration to hard terrain rules.
        for(var d:new RequirementExpander().expandMinimum(config).patches()) {
            ContentId id=d.allowedBiomes().getFirst();long available=climateArea(id);
            if(available>=d.area().target())continue;
            var preferred=preferences(config,id).keySet();
            Site center=sites.stream().filter(s->config.biomes().allows(id,s.sample)&&Math.hypot(s.x,s.z)>core*3)
                    .min(Comparator.comparingDouble(s->cost(id,s.x,s.z,s.sample))).orElse(null);
            if(center==null)continue;
            TemperatureType t=preferred.stream().min(Comparator.<TemperatureType>comparingDouble(v->Math.abs(v.ordinal()-typeAt(center.x,center.z,center.sample).ordinal())).thenComparingInt(TemperatureType::ordinal)).orElseThrow();
            double value=raw(center.x,center.z,center.sample),target=t==TemperatureType.COLD?cold-.12:t==TemperatureType.HOT?hot+.12:(cold+hot)/2;
            corrections.add(new Correction(center.x,center.z,Math.max(96,Math.sqrt(d.area().target()/Math.PI)*1.8),target-value));
        }
        for(var s:sites)actual[typeAt(s.x,s.z,s.sample).ordinal()]++;
        for(int i=0;i<3;i++)actual[i]/=sites.size();
        for(var d:new RequirementExpander().expandMinimum(config).patches()) {
            ContentId id=d.allowedBiomes().getFirst();
            long legal=sites.stream().filter(s->config.biomes().allows(id,s.sample)).count()*STEP*STEP;
            supply.add(new Supply(id.value(),d.area().target(),legal,climateArea(id)));
        }
        progress.accept(1);
    }
    private void distribute(ContentId id,double amount,double[] out) {
        var prefs=preferences(config,id);
        double[] shares=new double[3];
        for(var e:prefs.entrySet()) {
            double land=1;
            if(prefs.size()>1)land+=sites.stream().filter(s->config.biomes().allows(id,s.sample))
                    .filter(s->{double value=base(s.x,s.z,s.sample);return (value<-.25?TemperatureType.COLD:value>.25?TemperatureType.HOT:TemperatureType.MEDIUM)==e.getKey();}).count();
            shares[e.getKey().ordinal()]=e.getValue()*Math.sqrt(land);
        }
        double total=Arrays.stream(shares).sum();
        for(int i=0;i<3;i++)out[i]+=amount*shares[i]/total;
    }
    private void calibrate() {
        double[] values=sites.stream().mapToDouble(s->base(s.x,s.z,s.sample)).sorted().toArray();
        low=values[0];high=values[values.length-1];
        cold=values[Math.min(values.length-1,(int)(values.length*ratios[0]))];
        hot=values[Math.min(values.length-1,(int)(values.length*(ratios[0]+ratios[1])))];
        if(hot-cold<.01)hot=cold+.01;
    }
    private double supplyCost() {
        double result=0;
        for(var d:new RequirementExpander().expandMinimum(config).patches()) {
            long area=climateArea(d.allowedBiomes().getFirst());
            double deficit=Math.max(0,1-area/(double)d.area().target());result+=deficit*deficit;
        }
        return result;
    }
    private long climateArea(ContentId id) {
        var prefs=preferences(config,id);
        return sites.stream().filter(s->config.biomes().allows(id,s.sample)&&prefs.containsKey(typeAt(s.x,s.z,s.sample))).count()*STEP*STEP;
    }
    private static double[] blur(double[] source,int width,int radius) {
        double[] horizontal=new double[source.length],result=new double[source.length];
        for(int z=0;z<width;z++)for(int x=0;x<width;x++) {
            double sum=0,weight=0;
            for(int d=-radius;d<=radius;d++){double w=radius+1-Math.abs(d);sum+=source[z*width+Math.clamp(x+d,0,width-1)]*w;weight+=w;}
            horizontal[z*width+x]=sum/weight;
        }
        for(int z=0;z<width;z++)for(int x=0;x<width;x++) {
            double sum=0,weight=0;
            for(int d=-radius;d<=radius;d++){double w=radius+1-Math.abs(d);sum+=horizontal[Math.clamp(z+d,0,width-1)*width+x]*w;weight+=w;}
            result[z*width+x]=sum/weight;
        }
        return result;
    }
    private double elevation(double[] field,double x,double z) {
        double gx=Math.clamp((x-2)/STEP+heightExtent,0,heightWidth-1.000001),gz=Math.clamp((z-2)/STEP+heightExtent,0,heightWidth-1.000001);
        int ix=(int)gx,iz=(int)gz;double tx=gx-ix,tz=gz-iz;
        tx=tx*tx*(3-2*tx);tz=tz*tz*(3-2*tz);
        int i=iz*heightWidth+ix;
        return (field[i]*(1-tx)+field[i+1]*tx)*(1-tz)+(field[i+heightWidth]*(1-tx)+field[i+heightWidth+1]*tx)*tz;
    }
    /** Cooling follows broad mountain mass, slopes and actual local elevation at separate scales. */
    public double elevationCooling(double x,double z,MacroSample s) {
        double effective=.25*s.groundSurface()+.55*elevation(slopeHeight,x,z)+.20*elevation(regionalHeight,x,z);
        return .0048*Math.max(0,effective-76);
    }
    private double base(double x,double z,MacroSample s) {
        double wx=x+radius*.22*warpX.sample(x,z),wz=z+radius*.22*warpZ.sample(x,z);
        double slope=elevation(slopeHeight,x,z),mass=elevation(regionalHeight,x,z);
        // Terrain relief modulates broad noise: boundaries follow valleys and spurs without pixel noise.
        double relief=Math.clamp(Math.abs(slope-mass)/48,0,1);
        return .80*(wx*Math.cos(angle)+wz*Math.sin(angle))/radius
                +.42*regional.sample(wx,wz)+.14*detail.sample(wx,wz)
                +(.035+.055*relief)*foothills.sample(x,z)-elevationCooling(x,z,s);
    }
    private double raw(double x,double z,MacroSample s) {
        double value=base(x,z,s);
        for(var c:corrections)value+=c.delta*Math.exp(-Math.pow(Math.hypot(x-c.x,z-c.z)/c.radius,2)*2);
        double d=Math.hypot(x,z),influence=1-Math.clamp((d-core)/(core*3),0,1);
        influence=influence*influence*(3-2*influence);
        double target=spawnType==TemperatureType.COLD?cold-.15:spawnType==TemperatureType.HOT?hot+.15:(cold+hot)/2;
        return value*(1-influence)+target*influence;
    }
    public TemperatureType typeAt(double x,double z,MacroSample sample) {
        double v=raw(x,z,sample);return v<cold?TemperatureType.COLD:v>hot?TemperatureType.HOT:TemperatureType.MEDIUM;
    }
    public double valueAt(double x,double z,MacroSample sample) {
        double v=raw(x,z,sample);
        return Math.clamp(v<cold?3*(v-low)/Math.max(.01,cold-low):v>hot?7+3*(v-hot)/Math.max(.01,high-hot):3+4*(v-cold)/(hot-cold),0,10);
    }
    public double cost(ContentId id,double x,double z,MacroSample sample) {
        double value=valueAt(x,z,sample),best=Double.POSITIVE_INFINITY;
        var prefs=preferences(config,id);double max=prefs.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        for(var e:prefs.entrySet()) {
            double center=switch(e.getKey()){case COLD->1.5;case MEDIUM->5;case HOT->8.5;};
            double deviation=Math.max(0,Math.abs(center-value)-1.2);
            best=Math.min(best,deviation*deviation*.4-Math.log(e.getValue()/max)*.3);
        }
        var rule=config.biomes().terrainRules().get(id);
        return best+(rule==null?0:rule.heightCost(sample.groundSurface()));
    }
    public static Map<TemperatureType,Double> preferences(AdventureWorldConfig config,ContentId id) {
        var rule=config.biomes().terrainRules().get(id);
        return rule==null?Map.of(TemperatureType.MEDIUM,1.0):rule.temperatures();
    }
    public static double weight(AdventureWorldConfig config,ContentId id) {
        var rule=config.biomes().terrainRules().get(id);return rule==null?1:rule.fillerWeight();
    }
    public double[] targetRatios(){return ratios.clone();}
    public double[] actualRatios(){return actual.clone();}
    public List<Supply> supply(){return List.copyOf(supply);}
}
