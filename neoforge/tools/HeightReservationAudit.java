import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.planner.*;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import java.util.*;
/** Compare old clipping to fitted envelopes using the same reserved planning category and seeds. */
public class HeightReservationAudit {
 public static void main(String[] args) {
  System.out.println("version\tseed\tsamples\tmin_height\tmax_height\trelief\texact_cap_fraction\tflat_fraction");
  for(long seed:new long[]{9,7331,8844}) {
   var reserve=new TerrainCapacityPlan.Reservation(0,0,RegionTerrain.Template.PLATEAU,null,112.0,32768);
   var terrain=new RegionTerrain(seed,PlannerProfile.V2,new TerrainCapacityPlan(List.of(reserve)));
   int count=0,cap=0,flat=0;double min=1e9,max=-1e9;
   for(int x=-800;x<=800;x+=4)for(int z=-800;z<=800;z+=4) {
    if(!new RegionTerrain.GridKey(0,0).equals(terrain.interiorRegionAt(x,z,240)))continue;
    double h=64+terrain.sample(x,z).relativeHeight();count++;min=Math.min(min,h);max=Math.max(max,h);
    if(Math.abs(h-104)<1e-9)cap++;
    if(Math.abs(h-64-terrain.sample(x+1,z).relativeHeight())<.01&&Math.abs(h-64-terrain.sample(x,z+1).relativeHeight())<.01)flat++;
   }
   System.out.printf(Locale.ROOT,"%s\t%d\t%d\t%.4f\t%.4f\t%.4f\t%.5f\t%.5f%n",args[0],seed,count,min,max,max-min,cap/(double)count,flat/(double)count);
  }
 }
}
