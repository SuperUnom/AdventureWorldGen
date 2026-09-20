package io.github.luoyan.adventureworldgen.climate;

import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.plan.ClimateCorrection;
import io.github.luoyan.adventureworldgen.plan.ClimateState;
import io.github.luoyan.adventureworldgen.plan.ClimateSupply;
import io.github.luoyan.adventureworldgen.plan.HumidityState;
import io.github.luoyan.adventureworldgen.plan.TemperatureType;
import io.github.luoyan.adventureworldgen.plan.PlanningObserver;
import io.github.luoyan.adventureworldgen.plan.PlanningStage;
import io.github.luoyan.adventureworldgen.noise.ValueNoise;
import java.util.*;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlanningFailure;
import io.github.luoyan.adventureworldgen.noise.DeterministicRandom;
import io.github.luoyan.adventureworldgen.plan.FailureStage;

/** Frozen terrain climate. New plans use the accepted organic field; legacy states retain their formula. */
public final class ClimatePlan implements ClimateField {
    public static final int STEP=32;
    private final AdventureWorldConfig config;
    /**
     * The terrain this field was built from. The frozen quart memo re-derives a cell's sample from
     * it, so a cached value never depends on the sample a caller happened to pass first.
     */
    private final MacroTerrain terrain;
    private final String temperatureField;
    private final OrganicTemperatureField organic;
    private HumidityPlan humidity;
    private final ValueNoise regional, detail, warpX, warpZ, foothills;
    private final int heightExtent,heightWidth;
    private final double[] slopeHeight,regionalHeight;
    private final double radius, core;
    private double angle, low, high;
    private final double[] thresholds=new double[3];
    private boolean snowBoundary;
    private final FrozenQuartField frozenValues;
    private boolean frozen;
    private final TemperatureType spawnType;
    private final double[] ratios=new double[4], actual=new double[4];
    private final List<ClimateField.Site> sites=new ArrayList<>();
    private final ClimateStatistics demandStatistics;
    private final List<ClimateCorrection> corrections=new ArrayList<>();
    private final List<ClimateSupply> supply=new ArrayList<>();

    public ClimateState snapshot() {
        return new ClimateState(heightExtent,slopeHeight.clone(),regionalHeight.clone(),angle,low,high,
                thresholds.clone(),snowBoundary,spawnType,ratios.clone(),actual.clone(),List.copyOf(corrections),List.copyOf(supply),humidity.snapshot(),temperatureField);
    }
    public ClimatePlan(long seed,AdventureWorldConfig config,MacroTerrain terrain,ClimateStatistics statistics) {
        this(seed,config,terrain,ignored->{},statistics);
    }
    public ClimatePlan(long seed,AdventureWorldConfig config,MacroTerrain terrain,java.util.function.DoubleConsumer progress,
                       ClimateStatistics statistics) {
        this(seed,config,terrain,progress,null,statistics);
    }
    public ClimatePlan(long seed,AdventureWorldConfig config,MacroTerrain terrain,java.util.function.DoubleConsumer progress,
                       ClimateState frozen,ClimateStatistics statistics) {
        this(seed,config,terrain,progress,PlanningObserver.NONE,frozen,statistics);
    }
    public ClimatePlan(long seed,AdventureWorldConfig config,MacroTerrain terrain,java.util.function.DoubleConsumer progress,
                       PlanningObserver observer,ClimateState frozen,ClimateStatistics statistics) {
        this.config=config;
        this.terrain=terrain;
        this.demandStatistics=Objects.requireNonNull(statistics,"statistics");
        temperatureField=frozen==null?OrganicTemperatureField.VERSION:frozen.temperatureField();
        if(temperatureField!=null&&!OrganicTemperatureField.VERSION.equals(temperatureField))
            throw new IllegalArgumentException("unsupported frozen temperature field: "+temperatureField);
        organic=temperatureField==null?null:new OrganicTemperatureField(seed);
        radius=config.world().radius();core=Math.min(64,radius/8);
        frozenValues=new FrozenQuartField(radius+128);
        regional=new ValueNoise(seed,"climate/region",Math.max(128,radius*.48));
        detail=new ValueNoise(seed,"climate/detail",Math.max(96,radius*.13));
        warpX=new ValueNoise(seed,"climate/warp-x",Math.max(192,radius*.32));
        warpZ=new ValueNoise(seed,"climate/warp-z",Math.max(192,radius*.32));
        foothills=new ValueNoise(seed,"climate/foothills",Math.max(96,radius*.055));
        int extent=(int)Math.ceil(radius/STEP);
        if((2L*extent+1)*(2L*extent+1)>PlannerProfile.V2.maximumCostNodes())
            throw new PlanningFailure(PlanningFailure.Code.RESOURCE_LIMIT, FailureStage.CLIMATE,"environment grid exceeds budget");
        heightExtent=extent;heightWidth=extent*2+1;
        if(frozen!=null) {
            if(frozen.extent()!=extent || frozen.slopeHeight().length!=heightWidth*heightWidth
                    || frozen.regionalHeight().length!=heightWidth*heightWidth || frozen.thresholds().length!=3
                    || frozen.ratios().length!=4 || frozen.actual().length!=4 || frozen.spawnType()==null)
                throw new IllegalArgumentException("invalid frozen climate dimensions");
            for(double[] values:List.of(frozen.slopeHeight(),frozen.regionalHeight(),frozen.thresholds(),frozen.ratios(),frozen.actual()))
                for(double value:values)if(!Double.isFinite(value))throw new IllegalArgumentException("nonfinite frozen climate");
            if(!Double.isFinite(frozen.angle())||!Double.isFinite(frozen.low())||!Double.isFinite(frozen.high())
                    || frozen.thresholds()[0]>=frozen.thresholds()[1] || frozen.thresholds()[1]>=frozen.thresholds()[2])
                throw new IllegalArgumentException("invalid frozen climate thresholds");
            if(organic!=null&&(frozen.low()!=0||frozen.high()!=10
                    ||!Arrays.equals(frozen.thresholds(),new double[]{2.5,5,7.5})||!frozen.corrections().isEmpty()))
                throw new IllegalArgumentException("organic temperature state must retain fixed thresholds and no corrections");
            slopeHeight=frozen.slopeHeight().clone();regionalHeight=frozen.regionalHeight().clone();
            angle=frozen.angle();low=frozen.low();high=frozen.high();snowBoundary=frozen.snowBoundary();spawnType=frozen.spawnType();
            System.arraycopy(frozen.thresholds(),0,thresholds,0,3);
            System.arraycopy(frozen.ratios(),0,ratios,0,4);System.arraycopy(frozen.actual(),0,actual,0,4);
            for(var c:frozen.corrections())if(!Double.isFinite(c.x())||!Double.isFinite(c.z())||!Double.isFinite(c.radius())
                    ||!Double.isFinite(c.delta())||c.radius()<=0)throw new IllegalArgumentException("invalid climate correction");
            corrections.addAll(frozen.corrections());supply.addAll(frozen.supply());
            this.frozen=true;
            humidity=new HumidityPlan(seed,config,terrain,this,ignored->{},Objects.requireNonNull(frozen.humidity(),"missing frozen humidity"));
            return;
        }
        double[] heights=new double[heightWidth*heightWidth];
        for(int z=-extent;z<=extent;z++) {
            for(int x=-extent;x<=extent;x++) {
                int wx=x*STEP,wz=z*STEP;
                var s=terrain.sample(wx+2,wz+2);
                heights[(z+extent)*heightWidth+x+extent]=s.groundSurface();
                if(Math.hypot(wx,wz)>radius)continue;
                if(s.waterKind()==WaterKind.NONE&&!s.hazardous())sites.add(new ClimateField.Site(wx,wz,s));
            }
            progress.accept(.2*(z+extent+1)/(2*extent+1));
        }
        slopeHeight=blur(heights,heightWidth,3);regionalHeight=blur(heights,heightWidth,8);
        if(sites.isEmpty())throw new PlanningFailure(PlanningFailure.Code.NO_SOLUTION_IN_DOMAIN, FailureStage.CLIMATE,"no allocatable dry land");
        ContentId spawn=config.spawn().biome();
        spawnType=config.temperaturePreferences(spawn).entrySet().stream().max(Comparator.<Map.Entry<TemperatureType,Double>>comparingDouble(Map.Entry::getValue)
                .thenComparing(e->-e.getKey().ordinal())).orElseThrow().getKey();
        angle=(DeterministicRandom.mix(seed)>>>11)*0x1.0p-53*Math.PI*2;
        var statisticsSites=List.copyOf(sites);
        System.arraycopy(demandStatistics.targetRatios(this,statisticsSites),0,ratios,0,4);
        // Kept in the serialized state for compatibility; temperature is configured, not a snow test.
        snowBoundary=false;
        // Demand remains diagnostic. It does not reshape the accepted temperature field.
        angle=0;low=0;high=10;
        thresholds[0]=2.5;thresholds[1]=5;thresholds[2]=7.5;
        progress.accept(.8);
        this.frozen=true;
        System.arraycopy(demandStatistics.actualRatios(this,statisticsSites),0,actual,0,4);
        supply.addAll(demandStatistics.supply(this,statisticsSites));
        progress.accept(1);
        humidity=new HumidityPlan(seed,config,terrain,this,observer.within(PlanningStage.HUMIDITY),null);
    }
    /**
     * The authoritative classification: the temperature type of the accepted field, under this
     * field's own {@code thresholds}. Everything that needs a band reads this, and nothing
     * reconstructs a band from a raw value.
     */
    @Override public int band(int x,int z,MacroSample sample){ return typeAt(x,z,sample).ordinal(); }

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
        double effective=effectiveHeightAt(x,z,s);
        return organic==null?.0048*Math.max(0,effective-76):OrganicTemperatureField.cooling(effective);
    }
    public double effectiveHeightAt(double x,double z,MacroSample s) {
        return .25*s.groundSurface()+.55*elevation(slopeHeight,x,z)+.20*elevation(regionalHeight,x,z);
    }
    private double base(double x,double z,MacroSample s) {
        if(organic!=null)return organic.temperature(x,z,effectiveHeightAt(x,z,s));
        double wx=x+radius*.22*warpX.sample(x,z),wz=z+radius*.22*warpZ.sample(x,z);
        double slope=elevation(slopeHeight,x,z),mass=elevation(regionalHeight,x,z);
        // Terrain relief modulates broad noise: boundaries follow valleys and spurs without pixel noise.
        double relief=Math.clamp(Math.abs(slope-mass)/48,0,1);
        return .80*(wx*Math.cos(angle)+wz*Math.sin(angle))/radius
                +.42*regional.sample(wx,wz)+.14*detail.sample(wx,wz)
                +(.035+.055*relief)*foothills.sample(x,z)-elevationCooling(x,z,s);
    }
    /**
     * The accepted raw field value.
     *
     * <p>A frozen field memoises one value per quart cell, so the value must be a function of the
     * coordinate and the frozen state - never of the caller's sample. At a cacheable quart center
     * the sample is therefore re-derived from this field's own frozen terrain before it is stored;
     * an off-grid or out-of-extent coordinate is computed exactly from the sample it was given and
     * is not stored at all.
     *
     * <p>The public queries ({@link #typeAt}, {@link #valueAt}, {@link #band}) accept a sample
     * because callers already hold one, but at a quart center that argument cannot change the
     * result: passing the sample of a different position used to seed the whole cell.
     */
    private double raw(double x,double z,MacroSample s) {
        return frozen?frozenValues.get(x,z,()->computeRaw(x,z,s)):computeRaw(x,z,s);
    }
    private double computeRaw(double x,double z,MacroSample s) {
        double value=base(x,z,s);
        if(organic!=null)return value;
        for(var c:corrections)value+=c.delta()*Math.exp(-Math.pow(Math.hypot(x-c.x(),z-c.z())/c.radius(),2)*2);
        double d=Math.hypot(x,z),influence=1-Math.clamp((d-core)/(core*3),0,1);
        influence=influence*influence*(3-2*influence);
        double target=rawCenter(spawnType);
        value=value*(1-influence)+target*influence;
        return value;
    }
    private double rawCenter(TemperatureType type) {
        int i=type.ordinal();
        return i==0?thresholds[0]-.18:i==3?thresholds[2]+.18:(thresholds[i-1]+thresholds[i])/2;
    }
    public TemperatureType typeAt(double x,double z,MacroSample sample) {
        double v=raw(x,z,sample);
        for(int i=0;i<3;i++)if(v<thresholds[i])return TemperatureType.values()[i];
        return TemperatureType.HOT;
    }
    public double valueAt(double x,double z,MacroSample sample) {
        double v=raw(x,z,sample);
        if(organic!=null)return v;
        int band=0;
        while(band<3&&v>=thresholds[band])band++;
        double from=band==0?low:thresholds[band-1],to=band==3?high:thresholds[band];
        return Math.clamp(2.5*(band+(v-from)/Math.max(.01,to-from)),0,10);
    }
    public HumidityPlan humidity(){return humidity;}
    public double[] targetRatios(){return ratios.clone();}
    public double[] actualRatios(){return actual.clone();}
    public List<ClimateSupply> supply(){return List.copyOf(supply);}
}
