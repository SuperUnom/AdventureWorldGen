import io.github.luoyan.adventureworldgen.terrain.*;
import io.github.luoyan.adventureworldgen.planner.*;
import io.github.luoyan.adventureworldgen.plan.PlannerProfile;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
/** Exact frozen coastline at three nested scales; diagnostic, not a Minecraft screenshot. */
public class CoastDetailPreview {
 public static void main(String[] args) throws Exception {
  long seed=Long.parseLong(args[0]); var result=new CoastGenerator(PlannerProfile.V2).generate(seed,3000,288);
  var coast=result.coastline();
  var center=coast.vertices().stream().max(java.util.Comparator.comparingDouble(p->p.x())).orElseThrow();
  int size=512, header=52; var image=new BufferedImage(size*3,(size+header),BufferedImage.TYPE_INT_RGB);
  Graphics2D g=image.createGraphics();g.setColor(new Color(0x15232C));g.fillRect(0,0,image.getWidth(),image.getHeight());
  int[] spans={2048,512,128};
  for(int panel=0;panel<3;panel++) {
   double span=spans[panel];
   for(int z=0;z<size;z++)for(int x=0;x<size;x++) {
    double wx=center.x()+(x-size/2.0)*span/size,wz=center.z()+(z-size/2.0)*span/size;
    double distance=coast.signedDistance(wx,wz);
    image.setRGB(panel*size+x,header+z,distance>=0?(distance<3?0xDBC891:0x6E9270):0x285374);
   }
   g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.PLAIN,19));
   g.drawString("r9 / "+spans[panel]+" blocks wide",panel*size+18,32);
   if(panel<2){int box=(int)(spans[panel+1]/span*size);g.setColor(Color.WHITE);g.drawRect(panel*size+(size-box)/2,header+(size-box)/2,box,box);}
  }
  g.dispose();Path out=Path.of(args[1]);Files.createDirectories(out.getParent());ImageIO.write(image,"png",out.toFile());
  System.out.println("seed="+seed+", center="+center+", vertices="+result.vertexCount()+", error="+result.estimatedMaximumError());
 }
}
