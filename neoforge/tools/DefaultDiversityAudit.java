import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import java.nio.file.*;
import java.util.*;

/** Measures actual dry-land ownership and recipes, rather than treating weights as area ratios. */
public class DefaultDiversityAudit {
 public static void main(String[] args)throws Exception {
  var config=new AdventureWorldConfigParser().parse(Files.readString(Path.of(args[0])));
  var plan=GeneratedAdventurePlan.restore(config,new PlanV2Codec().decode(Files.readAllBytes(Path.of(args[1])),new ContentId("adventureworldgen:default"),"audit"));
  Map<String,Integer> recipes=new TreeMap<>(),biomes=new TreeMap<>();int total=0;
  int radius=(int)config.world().radius();
  for(int x=-radius;x<=radius;x+=16)for(int z=-radius;z<=radius;z+=16) {
   var sample=plan.terrainAt(x+2,z+2);if(sample.waterKind()!=WaterKind.NONE)continue;
   total++;recipes.merge(sample.recipe(),1,Integer::sum);biomes.merge(plan.biomeAt(x,64,z).value(),1,Integer::sum);
  }
  var result=new LinkedHashMap<String,Object>();result.put("seed",plan.seed());result.put("dry_samples",total);
  result.put("recipes",recipes);result.put("biomes",biomes);
  var json=new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result);
  Files.writeString(Path.of(args[2]),json+"\n");System.out.println(args[2]+" dry_samples="+total);
 }
}
