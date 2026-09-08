import io.github.luoyan.adventureworldgen.runtime.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.*;
import java.nio.file.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
import com.google.gson.JsonParser;
/** Renders the real saved plan's biome and coastline queries, without vegetation. */
public class BoundaryPreview {
 public static void main(String[] args) throws Exception {
  Path input=Path.of(args[0]), output=Path.of(args[1]); Files.createDirectories(output);
  String hash=JsonParser.parseString(Files.readString(input.resolve("manifest.json"))).getAsJsonObject().get("input_sha256").getAsString();
  var config=new AdventureWorldConfigParser().parse(Files.newBufferedReader(Path.of("neoforge/src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
  GeneratedAdventurePlan plan;
  try(var stream=new GZIPInputStream(Files.newInputStream(input.resolve("plan.json.gz")))) {
   plan=new PlanV2Codec().decode(stream.readAllBytes(),new ContentId("adventureworldgen:default"),hash,config);
  }
  var carrier=plan.biomePatches().stream().filter(p->p.biomeId().value().equals("minecraft:desert")).findFirst().orElseThrow();
  int cx=(carrier.minX()+carrier.maxXExclusive())/2,cz=(carrier.minZ()+carrier.maxZExclusive())/2;
  search: for(int z=-1800;z<1800;z+=32)for(int x=-1800;x<1800;x+=32) {
   if(plan.coastline().signedDistance(x,z)<300)continue;
   String a=plan.landBiomeAt(x-24,z).value(),b=plan.landBiomeAt(x+24,z).value();
   if((a.equals("minecraft:forest")&&b.equals("minecraft:desert"))||(b.equals("minecraft:forest")&&a.equals("minecraft:desert"))) {cx=x;cz=z;break search;}
  }
  var coast=plan.coastline().vertices().stream().max(java.util.Comparator.comparingDouble(p->p.x())).orElseThrow();
  render(plan,output.resolve("biomes.rgb"),cx-256,cz-256,1,512);
  render(plan,output.resolve("coast.rgb"),(int)coast.x()-256,(int)coast.z()-256,1,512);
  Files.writeString(output.resolve("coordinates.txt"),"Seed "+plan.seed()+"\nBiomes centered at "+cx+", "+cz+"\nCoast centered at "+(int)coast.x()+", "+(int)coast.z()+"\n512 x 512 blocks, one pixel per block. Saved plan queries; vegetation omitted.\n");
 }
 private static void render(GeneratedAdventurePlan plan,Path out,int x0,int z0,int step,int size)throws Exception {
  try(var data=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
   for(int z=0;z<size;z++)for(int x=0;x<size;x++) {
    String biome=plan.biomeAt(x0+x*step,64,z0+z*step).value();
    int rgb=switch(biome){case "minecraft:ocean"->0x285374;case "minecraft:river"->0x48A7D1;case "minecraft:frozen_river"->0xBCE7F0;case "minecraft:forest"->0x38684A;case "minecraft:desert"->0xD7C787;case "minecraft:snowy_plains"->0xDCE8DD;default->0x88A865;};
    data.writeByte(rgb>>16);data.writeByte(rgb>>8);data.writeByte(rgb);
   }
  }
 }
}
