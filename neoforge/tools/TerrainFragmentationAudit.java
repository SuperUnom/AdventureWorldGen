import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.erosion.*;
import io.github.luoyan.adventureworldgen.hydrology.*;
import io.github.luoyan.adventureworldgen.planner.*;
import java.nio.file.*;
import java.io.*;
/** Block-resolution stage ablation: raw recipe, recipe detail, frozen erosion + smoothing. */
public class TerrainFragmentationAudit {
 public static void main(String[] a)throws Exception {
  Path out=Path.of(a[0]);Files.createDirectories(out);
  for(long seed:new long[]{9,7331,8844})for(TerrainTemplate t:new TerrainTemplate[]{TerrainTemplate.PLAINS,TerrainTemplate.HILLS_1,TerrainTemplate.DALES,TerrainTemplate.MOUNTAINS_1,TerrainTemplate.PLATEAU}) {
   var r=new TerrainRecipes(seed);double amp=t.defaults().verticalAmplitude();
   MacroTerrain base=(x,z)->new MacroSample(86+amp*(r.shape(t,x,z,1)+r.detail(t,x,z,1)),Double.NaN,WaterKind.NONE,false,"probe",t.category(),"probe");
   var delta=new ErosionGenerator(PlannerProfile.V2,HydrologyProfile.FINITE_CONTINENT).generate(seed,base,-192,-192,8,49,49);
   var eroded=new ErodedTerrain(base,delta,"probe");
   for(int stage=0;stage<3;stage++) {
    double[] h=new double[256*256];String label=stage==0?"shape":stage==1?"detail":"eroded";
    try(var data=new DataOutputStream(Files.newOutputStream(out.resolve(seed+"-"+t.id()+"-"+label+".bin")))) {
     for(int x=0;x<256;x++)for(int z=0;z<256;z++) {double offset=a.length>1?Double.parseDouble(a[1]):.5;double px=x-128.0+offset,pz=z-128.0+offset;double v=stage==0?86+amp*r.shape(t,px,pz,1):stage==1?base.sample(px,pz).groundSurface():eroded.sample(px,pz).groundSurface();h[x*256+z]=v;data.writeFloat((float)v);}
    }
    int extrema=0,edges=0;double rough=0,min=1e9,max=-1e9;
    for(int x=1;x<255;x++)for(int z=1;z<255;z++) {int i=x*256+z;double v=h[i];min=Math.min(min,v);max=Math.max(max,v);double avg=(h[i-1]+h[i+1]+h[i-256]+h[i+256])/4;rough+=Math.abs(v-avg);if((v>h[i-1]&&v>h[i+1]&&v>h[i-256]&&v>h[i+256])||(v<h[i-1]&&v<h[i+1]&&v<h[i-256]&&v<h[i+256]))extrema++;if(Math.floor(v)!=Math.floor(h[i+1]))edges++;if(Math.floor(v)!=Math.floor(h[i+256]))edges++;}
    System.out.printf("%d %s %s extrema=%d edges=%d rough=%.5f relief=%.2f%n",seed,t.id(),label,extrema,edges,rough/(254*254),max-min);
   }
  }
 }
}
