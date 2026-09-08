import io.github.luoyan.adventureworldgen.runtime.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import com.google.gson.JsonParser;
/** Exports actual frozen biome areas and locations for a default-profile plan. */
public class PlanTerrainAudit {
 public static void main(String[] args) throws Exception {
  Path input=Path.of(args[0]), output=Path.of(args[1]);
  var config=new AdventureWorldConfigParser().parse(Files.newBufferedReader(Path.of("neoforge/src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
  String hash=JsonParser.parseString(Files.readString(input.resolve("manifest.json"))).getAsJsonObject().get("input_sha256").getAsString();
  GeneratedAdventurePlan plan;
  try(var stream=new GZIPInputStream(Files.newInputStream(input.resolve("plan.json.gz")))) {
   plan=new PlanV2Codec().decode(stream.readAllBytes(),new ContentId("adventureworldgen:default"),hash,config);
  }
  StringBuilder report=new StringBuilder("biome\tlevel\tx\tz\tterrain\tarea\tmin_height\tmax_height\n");
  for(var patch:plan.biomePatches()) {
   double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY; long area=0;
   for(int z=patch.minZ()+2;z<patch.maxZExclusive();z+=4)for(int x=patch.minX()+2;x<patch.maxXExclusive();x+=4) if(patch.contains(x,z)) {
    var sample=plan.terrainAt(x,z);
    if(!config.biomes().allows(patch.biomeId(),sample))throw new AssertionError("terrain violation "+patch.patchId());
    if(!plan.landBiomeAt(x,z).equals(patch.biomeId()))throw new AssertionError("ownership violation "+patch.patchId());
    min=Math.min(min,sample.groundSurface());max=Math.max(max,sample.groundSurface());area+=16;
   }
   int x=(patch.minX()+patch.maxXExclusive())/2,z=(patch.minZ()+patch.maxZExclusive())/2;
   report.append(patch.biomeId()).append('\t').append(patch.adventureLevel()).append('\t').append(x).append('\t').append(z).append('\t')
    .append(plan.terrainAt(x,z).terrainTemplate()).append('\t').append(area).append('\t').append(min).append('\t').append(max).append('\n');
  }
  Files.createDirectories(output.getParent());Files.writeString(output,report);
  System.out.println("Seed "+plan.seed()+": "+plan.biomePatches().size()+" complete patches validated; "+output);
 }
}
