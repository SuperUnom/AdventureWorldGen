package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.RoadSettings;
import io.github.luoyan.adventureworldgen.noise.DeterministicRandom;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.*;
import java.util.function.BiPredicate;

/** Constrained corner rounding and long gentle bends; endpoints and bridge spans stay anchored. */
final class RoadShape {
    private RoadShape() {}
    static List<Vec2> shape(List<Vec2> input, long seed, String id, RoadSettings settings, BiPredicate<Vec2,Vec2> dryLegal) {
        var simple=new ArrayList<Vec2>(); simple.add(input.getFirst());
        int at=0;
        while(at<input.size()-1) {
            int end=Math.min(input.size()-1,at+32);
            while(end>at+1 && !dryLegal.test(input.get(at),input.get(end)))end--;
            simple.add(input.get(end));at=end;
        }
        var rounded=new ArrayList<Vec2>(); rounded.add(simple.getFirst());
        for(int i=1;i<simple.size()-1;i++) {
            Vec2 a=simple.get(i-1),b=simple.get(i),c=simple.get(i+1);
            double before=distance(a,b),after=distance(b,c), radius=Math.min(12,Math.min(before,after)*.25);
            Vec2 start=lerp(b,a,radius/before),end=lerp(b,c,radius/after);
            var arc=new ArrayList<Vec2>();arc.add(start);
            for(int k=1;k<=8;k++){double t=k/8.;arc.add(lerp(lerp(start,b,t),lerp(b,end,t),t));}
            boolean legal=dryLegal.test(rounded.getLast(),start);
            for(int k=1;k<arc.size()&&legal;k++)legal=dryLegal.test(arc.get(k-1),arc.get(k));
            if(legal)rounded.addAll(arc);else rounded.add(b);
        }
        rounded.add(simple.getLast());
        var result=new ArrayList<Vec2>();result.add(rounded.getFirst());
        for(int i=1;i<rounded.size();i++) {
            Vec2 a=rounded.get(i-1),b=rounded.get(i);double length=distance(a,b);
            if(length<settings.bendSpacing()*.65 || !dryLegal.test(a,b) || settings.bendAmplitude()==0) {result.add(b);continue;}
            double sign=DeterministicRandom.sample(seed,"roads-v1","road-bend",id,i)<.5?-1:1;
            int lobes=Math.max(1,(int)Math.round(length/settings.bendSpacing()));
            List<Vec2> bend=null;
            for(double amplitude=settings.bendAmplitude();amplitude>=1;amplitude*=.5) {
                var candidate=new ArrayList<Vec2>();candidate.add(a);
                int count=(int)Math.ceil(length/4);
                for(int k=1;k<=count;k++) {
                    double t=k/(double)count;
                    // sin squared envelope gives zero endpoint offset and tangent deflection.
                    double offset=sign*amplitude*StrictMath.sin(Math.PI*t)*StrictMath.sin(Math.PI*t)
                            *StrictMath.sin(Math.PI*lobes*t);
                    candidate.add(new Vec2(a.x()+(b.x()-a.x())*t-(b.z()-a.z())/length*offset,
                            a.z()+(b.z()-a.z())*t+(b.x()-a.x())/length*offset));
                }
                boolean legal=length(candidate)<=length*settings.maximumBendDetour();
                for(int k=1;k<candidate.size()&&legal;k++)legal=dryLegal.test(candidate.get(k-1),candidate.get(k));
                if(legal){bend=candidate;break;}
            }
            if(bend==null)result.add(b);else result.addAll(bend.subList(1,bend.size()));
        }
        return List.copyOf(result);
    }
    static double distance(Vec2 a,Vec2 b){return StrictMath.hypot(a.x()-b.x(),a.z()-b.z());}
    static double length(List<Vec2> points){double l=0;for(int i=1;i<points.size();i++)l+=distance(points.get(i-1),points.get(i));return l;}
    private static Vec2 lerp(Vec2 a,Vec2 b,double t){return new Vec2(a.x()+(b.x()-a.x())*t,a.z()+(b.z()-a.z())*t);}
}
