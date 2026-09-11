import io.github.luoyan.adventureworldgen.runtime.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.*;
import java.nio.file.*;
import java.util.*;
import io.github.luoyan.adventureworldgen.planner.*;
import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import io.github.luoyan.adventureworldgen.plan.PlannedBiomePatch;
import io.github.luoyan.adventureworldgen.spatial.CellMask;
import java.util.zip.GZIPInputStream;
import com.google.gson.JsonParser;
/** Replays first-stage spatial allocation on saved default terrain and anchors.
 * Built-in config legality only; the Minecraft GameTests separately validate production adapters. */
public class AllocationReplayAudit {
 public static void main(String[] args) throws Exception {
  Path input=Path.of(args[0]), output=Path.of(args[1]);
  var config=new AdventureWorldConfigParser().parse(Files.newBufferedReader(Path.of("src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
  String hash=JsonParser.parseString(Files.readString(input.resolve("manifest.json"))).getAsJsonObject().get("input_sha256").getAsString();
  GeneratedAdventurePlan plan;
  try(var stream=new GZIPInputStream(Files.newInputStream(input.resolve("plan.json.gz")))) {
   plan=GeneratedAdventurePlan.restore(config,new PlanV2Codec().decode(stream.readAllBytes(),new ContentId("adventureworldgen:default"),hash));
  }
  // Replay the saved anchors against their same legal candidate domains, without erosion/cost replanning.
  var anchors=plan.biomePatches().stream().filter(p->p.mask()!=null).toList();
  var reservations=plan.biomePatches().stream().filter(p->p.mask()==null).toList();
  List<QuotaAssignment.Demand> demands=new ArrayList<>();
  for(var patch:anchors) {
   int minimum=patch.mask().size();
   double radius=Math.min(config.world().radius()*2,32+Math.sqrt(minimum*16/Math.PI)*1.65);
   int extent=(int)Math.ceil(radius/4)*4;
   double fringe=Math.min(16,Math.sqrt(minimum*16/Math.PI)*0.2);
   var noise=new EcotoneNoise(plan.seed(),"allocation/"+patch.patchId(),32);
   record Ranked(long cell,double score) {};
   List<Ranked> candidates=new ArrayList<>();
   for(int dz=-extent;dz<=extent;dz+=4)for(int dx=-extent;dx<=extent;dx+=4) {
    int x=patch.anchorX()+dx,z=patch.anchorZ()+dz;double distance=Math.hypot(dx,dz);
    if(distance>radius||Math.hypot(x,z)>config.world().radius())continue;
    if(reservations.stream().anyMatch(p->p.contains(x,z)))continue;
    var sample=plan.terrainAt(x+2,z+2);
    if(sample.waterKind()==io.github.luoyan.adventureworldgen.api.WaterKind.OCEAN||sample.hazardous()||!config.biomes().allows(patch.biomeId(),sample))continue;
    candidates.add(new Ranked(CellMask.key(x,z),distance+fringe*(noise.threshold(x+2,z+2)-0.5)));
   }
   candidates.sort(Comparator.comparingDouble(Ranked::score).thenComparingLong(Ranked::cell));
   demands.add(new QuotaAssignment.Demand(patch.patchId(),minimum,candidates.stream().mapToLong(Ranked::cell).toArray(),CellMask.key(patch.anchorX(),patch.anchorZ())));
  }
  var assignment=QuotaAssignment.assignSpatial(demands,100_000_000);
  List<PlannedBiomePatch> replay=new ArrayList<>();
  for(int i=0;i<anchors.size();i++) {
   var p=anchors.get(i);var mask=assignment.masks().get(i);
   int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
   for(long cell:mask.cells()) {int x=CellMask.x(cell),z=CellMask.z(cell);minX=Math.min(minX,x);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x+4);maxZ=Math.max(maxZ,z+4);}
   replay.add(new PlannedBiomePatch(p.patchId(),p.biomeId(),p.adventureLevel(),minX,minZ,maxX,maxZ,mask,p.anchorX(),p.anchorZ()));
  }
  StringBuilder report=new StringBuilder("biome\tarea\tcomponents\tlargest_fraction\tanchor_fraction\ttiny_cells\n");
  for(var patch:replay) {
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
  System.out.println("Seed "+plan.seed()+": "+replay.size()+" patch topologies measured; "+output);
 }
}
