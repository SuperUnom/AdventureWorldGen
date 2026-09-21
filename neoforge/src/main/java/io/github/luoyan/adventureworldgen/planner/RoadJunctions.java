package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.plan.RoadPlan;
import io.github.luoyan.adventureworldgen.spatial.Vec2;
import java.util.*;

/** Reuse a built corridor between repeated intersections instead of building a narrow lens. */
final class RoadJunctions {
    private static final double EPSILON=1e-7;
    private record Contact(double incoming,double existing,Vec2 point) {}
    private RoadJunctions() {}

    static List<Vec2> merge(List<Vec2> input,List<RoadPlan.Route> network,double detour) {
        List<Vec2> result=input;
        // Stable commit order is authoritative. One pass per accepted route bounds the work.
        for(var route:network) {
            var old=route.points();var contacts=new ArrayList<Contact>();
            for(int i=1;i<result.size();i++)for(int j=1;j<old.size();j++)
                intersect(result.get(i-1),result.get(i),old.get(j-1),old.get(j),i-1,j-1,contacts);
            contacts.sort(Comparator.comparingDouble(Contact::incoming).thenComparingDouble(Contact::existing));
            if(contacts.size()<2)continue;
            var merged=new ArrayList<Vec2>();double cursor=0;
            for(int i=0;i<contacts.size()-1;i++) {
                var first=contacts.get(i);
                if(first.incoming()<cursor-EPSILON)continue;
                Contact last=null;List<Vec2> reuse=null;
                // Prefer the longest reusable span, including multiple crossings of one road.
                for(int j=contacts.size()-1;j>i;j--) {
                    var candidate=contacts.get(j);
                    if(candidate.incoming()-first.incoming()<EPSILON)continue;
                    var replacement=section(old,first.existing(),candidate.existing(),first.point(),candidate.point());
                    double original=RoadShape.length(section(result,first.incoming(),candidate.incoming(),first.point(),candidate.point()));
                    if(RoadShape.length(replacement)<=original*detour+EPSILON) {
                        last=candidate;reuse=replacement;break;
                    }
                }
                if(last==null)continue;
                append(merged,section(result,cursor,first.incoming(),at(result,cursor),first.point()));
                append(merged,reuse);cursor=last.incoming();
            }
            if(!merged.isEmpty()) {
                append(merged,section(result,cursor,result.size()-1,at(result,cursor),result.getLast()));
                result=List.copyOf(merged);
            }
        }
        return result;
    }

    private static void intersect(Vec2 a,Vec2 b,Vec2 c,Vec2 d,int i,int j,List<Contact> contacts) {
        double ax=b.x()-a.x(),az=b.z()-a.z(),bx=d.x()-c.x(),bz=d.z()-c.z();
        double denominator=ax*bz-az*bx;
        if(Math.abs(denominator)>EPSILON) {
            double cx=c.x()-a.x(),cz=c.z()-a.z();
            double t=(cx*bz-cz*bx)/denominator,u=(cx*az-cz*ax)/denominator;
            if(t>=-EPSILON&&t<=1+EPSILON&&u>=-EPSILON&&u<=1+EPSILON) {
                t=Math.clamp(t,0,1);u=Math.clamp(u,0,1);
                contacts.add(new Contact(i+t,j+u,new Vec2(c.x()+u*bx,c.z()+u*bz)));
            }
        } else {
            // Collinear overlaps and shared endpoints must also count as contacts.
            endpoint(a,c,d,i,j,contacts,false);endpoint(b,c,d,i+1,j,contacts,false);
            endpoint(c,a,b,j,i,contacts,true);endpoint(d,a,b,j+1,i,contacts,true);
        }
    }
    private static void endpoint(Vec2 p,Vec2 a,Vec2 b,double position,int segment,List<Contact> out,boolean reverse) {
        double dx=b.x()-a.x(),dz=b.z()-a.z(),length=dx*dx+dz*dz;
        if(length<EPSILON)return;
        double t=((p.x()-a.x())*dx+(p.z()-a.z())*dz)/length;
        if(t<0||t>1||StrictMath.hypot(p.x()-a.x()-t*dx,p.z()-a.z()-t*dz)>EPSILON)return;
        out.add(reverse?new Contact(segment+t,position,p):new Contact(position,segment+t,p));
    }
    private static Vec2 at(List<Vec2> path,double position) {
        int index=(int)position;if(index>=path.size()-1)return path.getLast();
        var a=path.get(index);var b=path.get(index+1);double t=position-index;
        return new Vec2(a.x()+(b.x()-a.x())*t,a.z()+(b.z()-a.z())*t);
    }
    private static List<Vec2> section(List<Vec2> path,double from,double to,Vec2 start,Vec2 end) {
        var points=new ArrayList<Vec2>();points.add(start);
        if(from<=to)for(int i=(int)Math.floor(from)+1;i<to;i++)points.add(path.get(i));
        else for(int i=(int)Math.ceil(from)-1;i>to;i--)points.add(path.get(i));
        points.add(end);return points;
    }
    private static void append(List<Vec2> output,List<Vec2> points) {
        for(var point:points)if(output.isEmpty()||RoadShape.distance(output.getLast(),point)>EPSILON)output.add(point);
    }
}
