import io.github.luoyan.adventureworldgen.runtime.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import com.google.gson.JsonParser;
/** Visualizes saved required masks and the per-block selection used by the surface pass. */
public class BiomeDetailPreview {
 public static void main(String[] args) throws Exception {
  Path input=Path.of(args[0]), output=Path.of(args[1]);
  var config=new AdventureWorldConfigParser().parse(Files.newBufferedReader(Path.of("neoforge/src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
  String hash=JsonParser.parseString(Files.readString(input.resolve("manifest.json"))).getAsJsonObject().get("input_sha256").getAsString();
  GeneratedAdventurePlan plan;
  try(var stream=new GZIPInputStream(Files.newInputStream(input.resolve("plan.json.gz")))) {
   plan=new PlanV2Codec().decode(stream.readAllBytes(),new ContentId("adventureworldgen:default"),hash,config);
  }
  var patch=plan.biomePatches().stream().filter(p->p.biomeId().value().equals("minecraft:old_growth_pine_taiga")&&p.mask()!=null).findFirst().orElseThrow();
  int cx=patch.anchorX(),cz=patch.anchorZ();
  edge:for(int z=patch.minZ();z<patch.maxZExclusive();z+=4)for(int x=patch.minX();x<patch.maxXExclusive();x+=4)
   if(patch.contains(x,z)&&!patch.contains(x+4,z)&&!plan.landBiomeAt(x+12,z).equals(patch.biomeId())){cx=x;cz=z;break edge;}
  int side=520,margin=20,top=108,width=side*2+margin*3,height=side+top+60;
  var img=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=img.createGraphics();
  g.setColor(new java.awt.Color(0x152331));g.fillRect(0,0,width,height);g.setColor(java.awt.Color.WHITE);
  g.setFont(new java.awt.Font("SansSerif",java.awt.Font.BOLD,23));g.drawString("r14 | Required biome body and block-scale edge mixing",20,32);
  g.setFont(new java.awt.Font("SansSerif",java.awt.Font.PLAIN,16));g.drawString("Seed "+plan.seed()+" | "+patch.biomeId()+" | Carrier area "+patch.area()+" blocks",20,58);
  g.drawString("Frozen required ownership",20,86);g.drawString("Surface-biome selection, 1 pixel cell = 1 block",side+40,86);
  int span=Math.max(patch.maxXExclusive()-patch.minX(),patch.maxZExclusive()-patch.minZ())+80;
  int centerX=(patch.minX()+patch.maxXExclusive())/2,centerZ=(patch.minZ()+patch.maxZExclusive())/2;
  for(int z=0;z<side;z++)for(int x=0;x<side;x++) {
   int wx=centerX-span/2+x*span/side,wz=centerZ-span/2+z*span/side;
   img.setRGB(margin+x,top+z,patch.contains(wx,wz)?0x39866D:0xB4BCC5);
   int bx=cx-32+x*64/side,bz=cz-32+z*64/side;
   var id=plan.surfaceBiomeAt(bx,bz);
   int color=id.equals(patch.biomeId())?0x39866D:java.awt.Color.HSBtoRGB(Math.floorMod(id.hashCode(),360)/360f,0.42f,0.8f)&0xffffff;
   img.setRGB(side+margin*2+x,top+z,color);
  }
  g.setColor(java.awt.Color.WHITE);g.drawString("Left: "+span+" blocks wide | Right: 64 blocks wide, centered at "+cx+", "+cz,20,height-33);
  g.drawString("Saved-plan queries, not a Minecraft screenshot. Colors distinguish biome IDs, not block textures.",20,height-10);
  g.dispose();Files.createDirectories(output.getParent());javax.imageio.ImageIO.write(img,"png",output.toFile());System.out.println(output);
 }
}
