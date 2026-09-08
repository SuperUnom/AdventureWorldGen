import io.github.luoyan.adventureworldgen.runtime.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import com.google.gson.JsonParser;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
/** Render actual saved-plan biome queries. Run with each revision's classes for a fair comparison. */
public class EcotonePreview {
 public static void main(String[] args) throws Exception {
  Path input=Path.of(args[0]),output=Path.of(args[1]),camera=Path.of(args[2]);
  var config=new AdventureWorldConfigParser().parse(Files.newBufferedReader(Path.of("neoforge/src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
  String hash=JsonParser.parseString(Files.readString(input.resolve("manifest.json"))).getAsJsonObject().get("input_sha256").getAsString();
  GeneratedAdventurePlan plan;
  try(var stream=new GZIPInputStream(Files.newInputStream(input.resolve("plan.json.gz")))) {
   plan=new PlanV2Codec().decode(stream.readAllBytes(),new ContentId("adventureworldgen:default"),hash,config);
  }
  int cx=0,cz=0;
  if(Files.exists(camera)){String[] p=Files.readString(camera).trim().split(",");cx=Integer.parseInt(p[0]);cz=Integer.parseInt(p[1]);}
  else {
   boolean found=false;
   search:for(int z=-2200;z<2200;z+=16)for(int x=-2200;x<2200;x+=16){
    if(plan.coastline().signedDistance(x,z)<256)continue;
    boolean reserved=false;
    for(var p:plan.biomePatches()) if(x>p.minX()-180&&x<p.maxXExclusive()+180&&z>p.minZ()-180&&z<p.maxZExclusive()+180){reserved=true;break;}
    if(reserved)continue;
    String a=plan.biomeAt(x-32,64,z).value(),b=plan.biomeAt(x+32,64,z).value();
    if((a.equals("minecraft:savanna")&&b.equals("minecraft:stony_peaks"))||(b.equals("minecraft:savanna")&&a.equals("minecraft:stony_peaks"))){cx=x;cz=z;found=true;break search;}
   }
   if(!found)throw new IllegalStateException("No unreserved savanna/mountain boundary found");
   Files.createDirectories(camera.getParent());Files.writeString(camera,cx+","+cz);
  }
  int size=640,header=68,span=384;
  var image=new BufferedImage(size,size+header,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
  g.setColor(new Color(0x17232E));g.fillRect(0,0,size,size+header);
  g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.PLAIN,21));g.drawString(args[3]+" / "+span+" blocks wide",20,29);
  g.setFont(new Font("SansSerif",Font.PLAIN,16));g.drawString("Seed "+plan.seed()+"  |  "+cx+", "+cz,20,53);
  for(int z=0;z<size;z++)for(int x=0;x<size;x++){
   int wx=cx-span/2+x*span/size,wz=cz-span/2+z*span/size;
   int color=switch(plan.biomeAt(wx,64,wz).value()) {
    case "minecraft:ocean"->0x285374;case "minecraft:river"->0x48A7D1;case "minecraft:frozen_river"->0xBCE7F0;
    case "minecraft:stony_peaks","minecraft:jagged_peaks","minecraft:windswept_gravelly_hills"->0x858A93;
    case "minecraft:savanna","minecraft:savanna_plateau","minecraft:windswept_savanna"->0x94914D;
    case "minecraft:desert"->0xD7C787;case "minecraft:forest","minecraft:dark_forest","minecraft:taiga"->0x38684A;
    case "minecraft:snowy_plains","minecraft:snowy_slopes","minecraft:frozen_peaks","minecraft:grove"->0xDCE8DD;
    default->0x88A865;
   };
   image.setRGB(x,z+header,color);
  }
  g.dispose();Files.createDirectories(output.getParent());ImageIO.write(image,"png",output.toFile());
  System.out.println("Rendered saved plan: "+output+" centered at "+cx+","+cz);
 }
}
