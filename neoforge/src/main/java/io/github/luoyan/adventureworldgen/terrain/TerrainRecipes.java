package io.github.luoyan.adventureworldgen.terrain;

import java.util.*;
import java.util.function.DoubleUnaryOperator;
import io.github.luoyan.adventureworldgen.planner.DeterministicRandom;

/** FTF 43fd42a4 Populators/VolcanoPopulator function graphs with upstream Perlin/Ridge/Billow kernels.
 * Shapes have mathematical [0,1] bounds; block amplitude replaces upstream Levels/B/V units.
 * Only plateau adds post-terrace surface noise; other detail stays inside its recipe graph. See META-INF/NOTICE.
 */
public final class TerrainRecipes {
    @FunctionalInterface private interface Noise { double at(double x,double z); }
    private final EnumMap<TerrainTemplate,Noise> shapes=new EnumMap<>(TerrainTemplate.class);
    private final EnumMap<TerrainTemplate,Noise> details=new EnumMap<>(TerrainTemplate.class);
    private final long seed;
    private String prefix;
    private int sequence;
    private double buildingDetailStrength;
    public TerrainRecipes(long seed) { this(seed,TerrainSettings.defaults()); }
    public TerrainRecipes(long seed,TerrainSettings settings) {
        this.seed=seed;
        for(var t:TerrainTemplate.values()) {
            prefix="recipe-r21/"+t.id(); sequence=0;
            buildingDetailStrength=settings.get(t).detailStrength();
            shapes.put(t,memoize(build(t)));
            // Populators adds this surface layer ONLY to plateau, after its terraces.
            details.put(t,t==TerrainTemplate.PLATEAU?warp(perlin(20,3),40,2,20):(x,z)->0);
        }
    }
    public double shape(TerrainTemplate t,double x,double z,double scale) { return shapes.get(t).at(x/scale,z/scale); }
    public double detail(TerrainTemplate t,double x,double z,double scale) { return .05*details.get(t).at(x/scale,z/scale); }
    private Noise build(TerrainTemplate t) {
        return switch(t) {
            case STEPPE, PLAINS -> {
                Noise erosion=alpha(perlin(500,3,3.75),.45);
                Noise h=mul(perlin(250,1),erosion);
                h=warp(h,perlin(62.5,3,t==TerrainTemplate.STEPPE?3:3.5),perlin(62.5,3,t==TerrainTemplate.STEPPE?3:3.5),62.5);
                yield warp(h,256,1,t==TerrainTemplate.STEPPE?200:256);
            }
            case HILLS_1 -> warp(warp(mul(perlin(200,3),alpha(billow(400,3,2,.5),.5)),30,3,20),400,3,200);
            case HILLS_2 -> mul(warp(warp(mul(cubic(128,2),alpha(perlin(32,4),.075)),30,3,20),400,3,200),alpha(ridge(512,2,2,.975),.8));
            case DALES -> {
                Noise a=map(billow(300,4,4,.8),v->.75*powCurve(v,.5));
                Noise b=map(billow(350,3,4,.8),v->StrictMath.pow(v,1.25));
                Noise selector=map(perlin(400,1),v->unit(v,.3,.6));
                yield warp(map(blend(selector,a,b,.4,.75),v->StrictMath.pow(v,1.125)),300,1,100);
            }
            case PLATEAU -> {
                Noise valley=memoize(warp(warp(map(ridge(500,1,2,.975),v->1-v),100,1,150),20,1,15));
                Noise top=mul(map(warp(warp(ridge(150,3,2.45,.975),300,1,150),40,2,20),v->.15*v),map(valley,v->unit(v,.02,.1)));
                Noise h=add(mul(valley,map(cubic(500,1),v->.3+.6*v)),top);
                yield map(h,v->plateauTerrace(v/1.05));
            }
            case BADLANDS -> {
                Noise mask=map(perlin(270,3),v->unit(v,.35,.65));
                Noise hills=memoize(mul(warp(warp(ridge(275,4,2,.975),400,2,100),18,1,20),mask));
                Noise mod=memoize(map(warp(hills,100,1,50),v->.4*v));
                Noise low=add(map(hills,v->.6*steps(v,4,.6,.7,false)),mod);
                Noise high=add(map(hills,v->.6*steps(v,10,.6,.7,false)),mod);
                Noise detail=map(add(low,high),v->.5+.5*v);
                Noise shape=map(add(map(hills,v->.6*steps(v,4,.65,.75,true)),mul(hills,map(perlin(200,3),v->.4*v))),v->.6*v);
                yield map(mul(shape,detail),v->v/0.9);
            }
            case TORRIDONIAN -> {
                Noise plains=map(warp(warp(perlin(100,3),300,1,150),20,1,40),v->.15*v);
                Noise hills=map(warp(warp(perlin(150,4),300,1,200),20,2,20),TerrainRecipes::boost);
                Noise h=blend(perlin(200,3),plains,hills,.6,.6);
                yield map(advanced(h,map(perlin(120,1),v->v*.25),map(perlin(200,1),v->.5+.5*v),6,0,.3,.5,.25),TerrainRecipes::boost);
            }
            case MOUNTAINS_1 -> warp(mul(ridge(610,4,2.35,1.15),alpha(perlin(24,4),.075)),350,1,150);
            case MOUNTAINS_2, MOUNTAINS_3 -> {
                Noise cell=warp(map(worley(t==TerrainTemplate.MOUNTAINS_2?360:600,false),v->clamp(v*1.2)),200,2,100);
                Noise h=map(mul(mul(cell,alpha(perlin(10,1),.025)),alpha(ridge(125,4,2,.975),.37)),v->StrictMath.pow(v,1.1));
                yield t==TerrainTemplate.MOUNTAINS_2?h:advanced(h,map(perlin(50,1),v->v*.5),map(perlin(100,1),v->unit(v,.5,.95)),24,.2,.45,.45,.5);
            }
            case VOLCANO -> {
                long cellSeed=DeterministicRandom.seed(seed,"terrain-r21","worley",field(),0);
                Noise lookup=perlin(2,1);
                Noise limit=worleyLookup(700,cellSeed,lookup);
                Noise cone=map(worley(700,true,cellSeed),v->unit(powCurve(1-v,11),.475,1));
                cone=map(cone,v->v<.5?StrictMath.pow(v,1-.5*(1-v/.5)):v);
                cone=warp(cone,15,2,10);
                Noise low=map(warp(ridge(150,3,2,.975),30,1,30),v->.1*v);
                Noise c=cone;
                Noise volcano=(x,z)-> {
                    double heightLimit=limit.at(x,z),v=c.at(x,z)*heightLimit;
                    double rim=v>.94*heightLimit?.94*heightLimit*(1-(v-.94*heightLimit)/(.06*heightLimit)/5):v;
                    return (rim+low.at(x,z)*(1-unit(v,.15,.45)))/.65;
                };
                // Apply the broad region-domain warp to BOTH cone and height lookup. Without it,
                // the low cone envelope exposes straight Worley cell edges across the landscape.
                yield warp(volcano,480,2,360);
            }

        };
    }
    private String field() { return prefix+"/"+(sequence++); }
    private Noise perlin(double scale,int octaves) { return perlin(scale,octaves,2); }
    private Noise perlin(double scale,int octaves,double lacunarity) {
        int salt=noiseSeed(); double detail=buildingDetailStrength;
        double[] signals={1,.9,.83,.75,.64,.62,.61};
        double signal=signals[Math.min(octaves,signals.length-1)];
        double[] frequencies=new double[octaves],weights=new double[octaves];
        double weight=1,total=0,frequency=1/scale;
        for(int i=0;i<octaves;i++) {
            frequencies[i]=frequency;weights[i]=weight*(i==0?1:detail);total+=weights[i];
            frequency*=lacunarity;weight*=.5;
        }
        double normalization=total*signal;
        return (x,z)-> {
            double sum=0;
            for(int i=0;i<octaves;i++)sum+=weights[i]*TerraForgedNoise.perlin(x*frequencies[i],z*frequencies[i],salt+i);
            return clamp(.5+.5*sum/normalization);
        };
    }

    private int noiseSeed() { return (int)DeterministicRandom.seed(seed,"terrain-r21","ftf-noise",field(),0); }
    private Noise ridge(double scale,int octaves,double lacunarity,double gain) {
        int salt=noiseSeed(); double detail=buildingDetailStrength;
        double[] frequencies=new double[octaves],weights=new double[octaves],amps=new double[octaves];
        double spectral=1,amp=2,boundWeight=1,total=0,frequency=1/scale;
        for(int i=0;i<octaves;i++) {
            frequencies[i]=frequency;amps[i]=amp;weights[i]=spectral*(i==0?1:detail);
            total+=boundWeight*weights[i];boundWeight=clamp(boundWeight*amp);
            spectral/=lacunarity;frequency*=lacunarity;amp*=gain;
        }
        double normalization=total;
        return (x,z)-> {
            double value=0,weight=1;
            for(int i=0;i<octaves;i++) {
                double signal=1-StrictMath.abs(TerraForgedNoise.perlin(x*frequencies[i],z*frequencies[i],salt+i));
                signal=signal*signal*weight;weight=clamp(signal*amps[i]);
                value+=signal*weights[i];
            }
            return clamp(value/normalization);
        };
    }

    private Noise billow(double scale,int octaves,double lacunarity,double gain) { return map(ridge(scale,octaves,lacunarity,gain),v->1-v); }
    private Noise cubic(double scale,int octaves) {
        long salt=DeterministicRandom.seed(seed,"terrain-r21","cubic",field(),0);
        return (x,z)-> {
            double sum=0,total=0,weight=1;
            for(int o=0;o<octaves;o++) {
                double px=x/scale* (1<<o),pz=z/scale*(1<<o); long ix=(long)Math.floor(px),iz=(long)Math.floor(pz);
                double tx=px-ix,tz=pz-iz; double[] row=new double[4];
                for(int k=-1;k<=2;k++)row[k+1]=cubicLerp(hash(salt+o,ix-1,iz+k),hash(salt+o,ix,iz+k),hash(salt+o,ix+1,iz+k),hash(salt+o,ix+2,iz+k),tx);
                sum+=weight*cubicLerp(row[0],row[1],row[2],row[3],tz)/2.25;total+=weight;weight*=.5;
            }
            return .5+.5*sum/total;
        };
    }
    private Noise worley(double scale,boolean ratio) {
        return worley(scale,ratio,DeterministicRandom.seed(seed,"terrain-r21","worley",field(),0));
    }
    private record CellularWindow(long x,long z,double[] xs,double[] zs) {}
    private static CellularWindow cellularWindow(ThreadLocal<CellularWindow> cache,long salt,long gx,long gz) {
        var window=cache.get();
        if(window!=null&&window.x==gx&&window.z==gz)return window;
        double[] xs=new double[25],zs=new double[25];int i=0;
        for(long iz=gz-2;iz<=gz+2;iz++)for(long ix=gx-2;ix<=gx+2;ix++) {
            xs[i]=ix+.5+.45*hash(salt,ix,iz);zs[i++]=iz+.5+.45*hash(salt+1,ix,iz);
        }
        window=new CellularWindow(gx,gz,xs,zs);cache.set(window);return window;
    }
    private Noise worley(double scale,boolean ratio,long salt) {
        ThreadLocal<CellularWindow> cache=new ThreadLocal<>();
        return (x,z)-> {
            double px=x/scale,pz=z/scale;long gx=(long)Math.floor(px),gz=(long)Math.floor(pz);
            var window=cellularWindow(cache,salt,gx,gz);
            double d1=Double.POSITIVE_INFINITY,d2=d1;
            for(int i=0;i<25;i++) {
                double dx=window.xs[i]-px,dz=window.zs[i]-pz,d=dx*dx+dz*dz;
                if(d<d1){d2=d1;d1=d;}else if(d<d2)d2=d;
            }
            return ratio?d1/d2:clamp(d2/2);
        };
    }
    private Noise worleyLookup(double scale,long salt,Noise lookup) {
        ThreadLocal<CellularWindow> cache=new ThreadLocal<>();
        return (x,z)-> {
            double px=x/scale,pz=z/scale,best=Double.POSITIVE_INFINITY,nearestX=0,nearestZ=0;
            long gx=(long)Math.floor(px),gz=(long)Math.floor(pz);
            var window=cellularWindow(cache,salt,gx,gz);
            for(int i=0;i<25;i++) {
                double cx=window.xs[i],cz=window.zs[i],dx=cx-px,dz=cz-pz,d=dx*dx+dz*dz;
                if(d<best){best=d;nearestX=cx;nearestZ=cz;}
            }
            return .45+.2*lookup.at(nearestX,nearestZ);
        };
    }
    /** Shared nodes in the recipe DAG are evaluated once for each exact coordinate.
     * Four recent entries cover both the original and nested warped evaluations. */
    private static Noise memoize(Noise noise) {
        class Recent {
            final long[] xs=new long[4],zs=new long[4];final double[] values=new double[4];int count,next;
        }
        ThreadLocal<Recent> cache=ThreadLocal.withInitial(Recent::new);
        return (x,z)-> {
            var recent=cache.get();long bx=Double.doubleToRawLongBits(x),bz=Double.doubleToRawLongBits(z);
            for(int i=0;i<recent.count;i++)if(recent.xs[i]==bx&&recent.zs[i]==bz)return recent.values[i];
            double value=noise.at(x,z);int i=recent.next;
            recent.xs[i]=bx;recent.zs[i]=bz;recent.values[i]=value;
            recent.next=(i+1)&3;recent.count=Math.min(4,recent.count+1);return value;
        };
    }
    private Noise warp(Noise h,double scale,int octaves,double amplitude) { return warp(h,perlin(scale,octaves),perlin(scale,octaves),amplitude); }
    private static Noise warp(Noise h,Noise wx,Noise wz,double amplitude) { return (x,z)->h.at(x+(wx.at(x,z)-.5)*amplitude,z+(wz.at(x,z)-.5)*amplitude); }
    private static Noise map(Noise h,DoubleUnaryOperator f) { return (x,z)->f.applyAsDouble(h.at(x,z)); }
    private static Noise mul(Noise a,Noise b) { return (x,z)->a.at(x,z)*b.at(x,z); }
    private static Noise add(Noise a,Noise b) { return (x,z)->a.at(x,z)+b.at(x,z); }
    private static Noise alpha(Noise h,double a) { return map(h,v->1-a+a*v); }
    private static Noise blend(Noise s,Noise a,Noise b,double mid,double range) {
        return (x,z)->{double t=unit(s.at(x,z),Math.max(0,mid-range/2),Math.min(1,mid+range/2));return lerp(a.at(x,z),b.at(x,z),t);};
    }
    private static Noise advanced(Noise h,Noise modulation,Noise mask,int steps,double lo,double hi,double slope,double modulationMax) {
        return (x,z)-> {
            double v=h.at(x,z),t=mask.at(x,z)*unit(v,lo,hi);
            // Upstream rounds before applying slope and additive modulation. A narrow continuous
            // rounding window avoids instantaneous block-height jumps while retaining the terraces.
            double scaled=v*steps,lower=Math.floor(scaled),fraction=scaled-lower;
            double stepped=(lower+smooth(unit(fraction,.4,.6)))/steps;
            double result=(stepped+(v-stepped)*slope+modulation.at(x,z))/(1+modulationMax);
            return lerp(v,result,t);
        };
    }
    private static double steps(double v,int count,double lo,double hi,boolean curve) {
        double inverted=1-v,lower=Math.floor(inverted*count)/count;
        double alpha=unit((inverted-lower)*count,lo,hi);
        return 1-lerp(lower,inverted,curve?smooth(alpha):alpha);
    }
    private static double plateauTerrace(double v) {
        // Four levels, with independent ramp, cliff and ramp-height controls from makePlateau.
        double scaled=v*3,lower=Math.floor(scaled),fraction=scaled-lower;
        double blendRange=.4;
        double alpha=unit(fraction,blendRange/2,1-blendRange/2),ramp=1-.9*.5,cliff=1-.15*.5;
        double value=lower/3,next=(lower+1)/3;
        if(alpha>ramp)value+=(next-value)*unit(alpha,ramp,1)*.35;
        if(alpha>cliff)value=lerp(value,next,unit(alpha,cliff,1));
        return value;
    }
    private static double powCurve(double v,double p) { double s=2*v-1;return .5+.5*StrictMath.copySign(StrictMath.pow(StrictMath.abs(s),p),s); }
    private static double boost(double v) { return StrictMath.pow(v,1-v); }
    private static double cubicLerp(double a,double b,double c,double d,double t) {double p=(d-c)-(a-b);return p*t*t*t+((a-b)-p)*t*t+(c-a)*t+b;}
    private static double hash(long seed,long x,long z) {long h=seed^x*0x9e3779b97f4a7c15L^z*0xc2b2ae3d27d4eb4fL;h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;return ((h^(h>>>31))>>>11)*0x1.0p-52-1;}
    static double clamp(double v) {return StrictMath.max(0,StrictMath.min(1,v));}
    static double unit(double v,double lo,double hi) {return clamp((v-lo)/(hi-lo));}
    static double smooth(double v) {return v*v*(3-2*v);}
    static double lerp(double a,double b,double t) {return a+(b-a)*t;}
}
