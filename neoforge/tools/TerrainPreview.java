/* Diagnostic preview of continuous terrain before erosion; not a Minecraft screenshot. */
import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlanDiagnostics;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import io.github.luoyan.adventureworldgen.hydrology.*;
import io.github.luoyan.adventureworldgen.runtime.*;
import io.github.luoyan.adventureworldgen.planner.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.api.*;
import java.util.*;
import java.nio.file.*;
import java.io.*;
public class TerrainPreview {
 public static void main(String[] args) throws Exception {
  long seed=7331;
  var config=new AdventureWorldConfigParser().parse(Files.newBufferedReader(Path.of("src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
  var c=new CoastGenerator(PlannerProfile.V2).generate(seed,3000,300);
  var island=new IslandMacroTerrain(c.coastline(),new RegionTerrain(seed,PlannerProfile.V2),seed,64,c.landBand(),c.seaBand(),"r5");
  var rivers=new HydrologyGenerator(PlannerProfile.V2,HydrologyProfile.FTF_ADAPTED_V1).generate(seed,3000,64,c.coastline(),island);
  var plan=new GeneratedAdventurePlan(seed,config,c.coastline(),rivers,64,c.landBand(),c.seaBand(),"r5",null,
   List.of(new PlannedBiomePatch("spawn",new ContentId("minecraft:plains"),0,-184,-184,184,184)),List.of(),PlanDiagnostics.basic(c.coastline().vertices().size(),rivers.channels().size(),rivers.channels().stream().mapToLong(channel->channel.points().size()).sum(),"r5"),null);
  Path dir=Path.of("build/reports/terrain-r6"); Files.createDirectories(dir);
  try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(dir.resolve("map.bin"))))) {
   for(int z=-3200;z<3200;z+=8) for(int x=-3200;x<3200;x+=8) {
    var h=plan.terrainAt(x+.5,z+.5);out.writeFloat((float)h.groundSurface());out.writeFloat((float)h.waterSurface());
    out.writeByte(h.waterKind().ordinal());out.writeByte(switch(plan.biomeAt(x,64,z).value()){case "minecraft:forest"->1;case "minecraft:desert"->2;case "minecraft:snowy_plains"->3;case "minecraft:ocean"->4;default->0;});
   }
  }
  try(var out=Files.newBufferedWriter(dir.resolve("coast.csv"))) {for(var p:c.coastline().vertices()) out.write(p.x()+","+p.z()+"\n");}
  double maxJump=0;String at="";
  var hydrated=new HydrologyTerrain(island,rivers);
  for(var ch:rivers.channels()) for(var p:ch.points()) for(int dx=-24;dx<=24;dx+=8) for(int dz=-24;dz<=24;dz+=8) {
   double x=p.x()+dx,z=p.z()+dz;
   double jump=Math.abs(hydrated.sample(x,z).groundSurface()-hydrated.sample(x+1,z).groundSurface());
   if(jump>maxJump){maxJump=jump;at=x+","+z;}
  }
  System.out.println("Max 1-block river neighborhood height difference: "+maxJump+" at "+at+"; rivers="+rivers.channels().size());
 }
}
