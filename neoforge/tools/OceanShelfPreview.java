import io.github.luoyan.adventureworldgen.terrain.OceanBathymetry;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Direct samples of the implemented ocean depth field, not a game screenshot. */
public class OceanShelfPreview {
 public static void main(String[] args)throws Exception {
  var field=new OceanBathymetry(7993);var im=new BufferedImage(1120,560,BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();
  g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
  g.setColor(new Color(0x132534));g.fillRect(0,0,1120,560);
  g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,24));g.drawString("r33 | Shallows, shelves and deep seafloor relief",32,38);
  g.setFont(new Font("SansSerif",0,15));g.drawString("Seed 7993 | seaBand = 256 | Three sections through the same continuous height field",32,68);
  g.setColor(new Color(0x224D68));g.fillRect(80,110,1000,350);
  for(int depth=0;depth<=80;depth+=20) {
   int y=110+depth*4;g.setColor(new Color(0x476575));g.drawLine(80,y,1080,y);
   g.setColor(Color.WHITE);g.drawString(Integer.toString(depth),42,y+5);
  }
  for(int d=0;d<=1200;d+=200) {int x=80+d*1000/1200;g.setColor(Color.WHITE);g.drawString(Integer.toString(d),x-10,488);}
  Color[] colors={new Color(0xFFC979),new Color(0xA4D8AC),new Color(0x96CFFA)};
  for(int row=0;row<3;row++) {
   var path=new Path2D.Double();
   for(int x=0;x<=1000;x++) {
    double d=x*1.2,y=110+4*field.depth(d,row*600,d,256);
    if(x==0)path.moveTo(80+x,y);else path.lineTo(80+x,y);
   }
   g.setColor(colors[row]);g.setStroke(new BasicStroke(2.2f));g.draw(path);
  }
  g.setColor(Color.WHITE);g.drawString("Depth",23,94);g.drawString("Distance offshore (blocks)",460,519);
  g.drawString("Shallows",82,101);g.drawString("Shelf",178,101);g.drawString("Slope",355,101);g.drawString("Deep basins and hills",700,101);
  g.setFont(new Font("SansSerif",0,13));g.drawString("Code-generated profile; shelf widths vary by location. Gentle depth tiers retain underwater relief.",80,547);
  g.dispose();Path out=Path.of(args[0]);Files.createDirectories(out.getParent());ImageIO.write(im,"png",out.toFile());
 }
}
