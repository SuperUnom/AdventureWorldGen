import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.biome.BiomeEnvironmentRules;
import java.nio.file.*;
import java.util.*;
/** Checks the final quart biome queries of a frozen DemandPlannerAudit plan. */
public class HumidityAudit {
 public static void main(String[] args)throws Exception {
  var config=new AdventureWorldConfigParser().parse(Files.newBufferedReader(Path.of("src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
  var plan=GeneratedAdventurePlan.restore(config,new PlanV2Codec().decode(Files.readAllBytes(Path.of(args[0])),new ContentId("adventureworldgen:default"),"audit"));
  long desert=0,beach=0,snowBeach=0,riverSand=0,riverOther=0,seaSand=0,seaOther=0,checked=0;
  var environment=new BiomeEnvironmentRules(config,plan.climate());
  for(int z=-3000;z<=3000;z+=8)for(int x=-3000;x<=3000;x+=8) {
   int qx=x+2,qz=z+2;var sample=plan.terrainAt(qx,qz);if(sample.waterKind()!=WaterKind.NONE)continue;
   var id=plan.biomeAt(x,64,z);checked++;
   if(!environment.allows(id,qx,qz,sample))throw new AssertionError("illegal runtime biome "+id+" at "+qx+","+qz);
   boolean sand=id.value().equals("minecraft:beach")||id.value().equals("minecraft:snowy_beach");
   if(id.value().equals("minecraft:desert"))desert++;
   if(id.value().equals("minecraft:beach"))beach++;
   if(id.value().equals("minecraft:snowy_beach"))snowBeach++;
   double fresh=plan.climate().humidity().freshDistanceAt(qx,qz),ocean=plan.climate().humidity().oceanDistanceAt(qx,qz);
   if(fresh<36){if(sand)riverSand++;else riverOther++;}
   if(ocean<36){if(sand)seaSand++;else seaOther++;}
  }
  if(desert==0||beach==0||snowBeach==0||riverSand==0||riverOther==0||seaSand==0||seaOther==0)throw new AssertionError("missing required mix");
  System.out.printf("PASS dry samples=%d desert=%d beach=%d snowy_beach=%d river_banks(sand/other)=%d/%d sea_banks(sand/other)=%d/%d%n",checked,desert,beach,snowBeach,riverSand,riverOther,seaSand,seaOther);
 }
}
