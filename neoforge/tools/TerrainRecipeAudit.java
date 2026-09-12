import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.planner.*;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import java.nio.file.*;
import java.util.*;

/** Fixed-seed recipe and layout statistics. Flat fraction measures <= .01 blocks/block in both axes. */
public class TerrainRecipeAudit {
 public static void main(String[] args)throws Exception {
  Path output=Path.of(args[0]);Files.createDirectories(output);
  var config=new AdventureWorldConfigParser().parse(Files.readString(Path.of("src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
  var report=new StringBuilder("seed\ttemplate\tmin\tmax\trelief\tslope_p50\tslope_p95\tmax_slope\tflat_fraction\n");
  var distribution=new StringBuilder("seed\ttemplate\tcells_32\tcomposite_cells\n");
  for(int a=1;a<args.length;a++) {
   long seed=Long.parseLong(args[a]);var recipes=new TerrainRecipes(seed);
   for(var t:TerrainTemplate.values()) {
    var s=config.world().terrain().get(t);var slopes=new ArrayList<Double>();double min=1e9,max=-1e9;int flat=0;
    for(int x=-1200;x<=1200;x+=8)for(int z=-1200;z<=1200;z+=8) {
     double h=height(recipes,t,s,x,z),dx=height(recipes,t,s,x+1,z)-h,dz=height(recipes,t,s,x,z+1)-h;
     min=Math.min(min,h);max=Math.max(max,h);slopes.add(Math.hypot(dx,dz));if(Math.abs(dx)<=.01&&Math.abs(dz)<=.01)flat++;
    }
    Collections.sort(slopes);report.append(String.format(Locale.ROOT,"%d\t%s\t%.3f\t%.3f\t%.3f\t%.4f\t%.4f\t%.4f\t%.5f%n",seed,t.id(),min,max,max-min,slopes.get(slopes.size()/2),slopes.get(slopes.size()*95/100),slopes.getLast(),flat/(double)slopes.size()));
   }
   var coast=new CoastGenerator(PlannerProfile.V2).generate(seed,config.world().radius(),288);
   var capacity=TerrainCapacitySolver.reserve(PlannerProfile.V2,seed,config,coast.coastline(),coast.landBand());
   var terrain=new RegionTerrain(seed,PlannerProfile.V2,capacity,config.world().terrain(),config.fillerTerrainPolicy());
   var counts=new EnumMap<TerrainTemplate,long[]>(TerrainTemplate.class);for(var t:TerrainTemplate.values())counts.put(t,new long[2]);
   for(int x=-3000;x<=3000;x+=32)for(int z=-3000;z<=3000;z+=32)if(coast.coastline().contains(x,z)) {
    var s=terrain.sample(x,z);counts.get(s.recipe())[0]++;if(s.secondaryWeight()>0)counts.get(s.recipe())[1]++;
   }
   counts.forEach((t,n)->distribution.append(seed+"\t"+t.id()+"\t"+n[0]+"\t"+n[1]+"\n"));
   System.out.println("MEASURED seed="+seed+" ranges="+capacity.ranges().ranges().size()+" reservations="+capacity.reservations().size());
  }
  Files.writeString(output.resolve("recipe-metrics.tsv"),report);Files.writeString(output.resolve("template-distribution.tsv"),distribution);
 }
 private static double height(TerrainRecipes r,TerrainTemplate t,TerrainTemplate.Settings s,double x,double z) {
  return 86+s.verticalAmplitude()*(r.shape(t,x,z,s.horizontalScale())+s.detailStrength()*r.detail(t,x,z,s.horizontalScale()));
 }
}
