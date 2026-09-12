import io.github.luoyan.adventureworldgen.runtime.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import com.google.gson.JsonParser;
/** Exports actual frozen biome areas and locations for a default-profile plan. */
public class PlanPatchTopologyAudit {
 public static void main(String[] args) throws Exception {
  Path input=Path.of(args[0]), output=Path.of(args[1]);
  var config=new AdventureWorldConfigParser().parse(Files.newBufferedReader(Path.of("src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
  String hash=JsonParser.parseString(Files.readString(input.resolve("manifest.json"))).getAsJsonObject().get("input_sha256").getAsString();
  GeneratedAdventurePlan plan;
  try(var stream=new GZIPInputStream(Files.newInputStream(input.resolve("plan.json.gz")))) {
   plan=GeneratedAdventurePlan.restore(config,new PlanV2Codec().decode(stream.readAllBytes(),new ContentId("adventureworldgen:default"),hash));
  }
  StringBuilder report=new StringBuilder("biome\tarea\tcomponents\tlargest_fraction\tanchor_fraction\ttiny_cells\n");
  for(var patch:plan.biomePatches()) {
   var cells=new java.util.HashSet<Long>();
   for(int z=patch.minZ();z<patch.maxZExclusive();z+=4)for(int x=patch.minX();x<patch.maxXExclusive();x+=4)
    if(patch.contains(x+2,z+2))cells.add(io.github.luoyan.adventureworldgen.spatial.CellMask.key(x,z));
   int count=0,largest=0,anchorArea=0,tiny=0,total=cells.size();
   long anchor=io.github.luoyan.adventureworldgen.spatial.CellMask.key(patch.anchorX(),patch.anchorZ());
   while(!cells.isEmpty()) {
    long first=cells.iterator().next();cells.remove(first);
    var queue=new java.util.ArrayDeque<Long>();queue.add(first);int area=0;boolean hasAnchor=false;
    while(!queue.isEmpty()) {
     long cell=queue.removeFirst();area++;hasAnchor|=cell==anchor;
     int x=io.github.luoyan.adventureworldgen.spatial.CellMask.x(cell),z=io.github.luoyan.adventureworldgen.spatial.CellMask.z(cell);
     for(int[] d:new int[][]{{4,0},{-4,0},{0,4},{0,-4}}) {
      long next=io.github.luoyan.adventureworldgen.spatial.CellMask.key(x+d[0],z+d[1]);
      if(cells.remove(next))queue.add(next);
     }
    }
    count++;largest=Math.max(largest,area);if(hasAnchor)anchorArea=area;if(area<=16)tiny+=area;
   }
   report.append(patch.biomeId()).append('\t').append(total*16).append('\t').append(count).append('\t')
    .append(largest/(double)total).append('\t').append(anchorArea/(double)total).append('\t').append(tiny).append('\n');
  }
  Files.createDirectories(output.getParent());Files.writeString(output,report);
  System.out.println("Seed "+plan.seed()+": "+plan.biomePatches().size()+" patch topologies measured; "+output);
 }
}
