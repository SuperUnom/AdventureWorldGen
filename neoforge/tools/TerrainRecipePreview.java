import io.github.luoyan.adventureworldgen.terrain.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Scientific height/shade atlas of the actual recipe functions, using common scale and illumination. */
public class TerrainRecipePreview {
 public static void main(String[] args)throws Exception {
  long seed=Long.parseLong(args[0]);Path output=Path.of(args[1]);
  var recipes=new TerrainRecipes(seed);int side=360,pad=20,title=48,w=3*(side+pad)+pad,h=4*(side+title+pad)+80;
  var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
  g.setColor(new Color(0x152331));g.fillRect(0,0,w,h);g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,22));
  g.drawString("Terrain r21 | Actual recipe height fields | Seed "+seed,20,30);
  g.setFont(new Font("SansSerif",Font.PLAIN,13));g.drawString("Each panel: 2048 x 2048 blocks. Shared elevation palette and light. Before erosion, water and regional blending.",20,53);
  int i=0;
  for(var t:TerrainTemplate.values()) {
   int px=pad+(i%3)*(side+pad),py=80+(i/3)*(side+title+pad);var settings=t.defaults();
   g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,16));g.drawString(t.id(),px,py+18);
   g.setFont(new Font("SansSerif",Font.PLAIN,12));g.drawString(t.category()+" | amplitude "+(int)settings.verticalAmplitude()+" blocks",px,py+35);
   for(int x=0;x<side;x++)for(int z=0;z<side;z++) {
    double wx=(x-side/2.0)*2048/side,wz=(z-side/2.0)*2048/side;
    double height=height(recipes,t,wx,wz),dx=(height(recipes,t,wx+4,wz)-height)/4,dz=(height(recipes,t,wx,wz+4)-height)/4;
    double shade=Math.clamp((-.45*dx-.55*dz+1)/Math.sqrt(1+dx*dx+dz*dz)*.55+.45,.25,1.25);
    int base=height<110?0x8CAD80:height<155?0xBCB18E:height<205?0xA6A39B:0xE1E5E5;
    int r=(int)Math.clamp(((base>>16)&255)*shade,0,255),green=(int)Math.clamp(((base>>8)&255)*shade,0,255),b=(int)Math.clamp((base&255)*shade,0,255);
    image.setRGB(px+x,py+title+z,(r<<16)|(green<<8)|b);
   }
   i++;
  }
  g.dispose();Files.createDirectories(output.getParent());ImageIO.write(image,"png",output.toFile());System.out.println(output);
 }
 private static double height(TerrainRecipes r,TerrainTemplate t,double x,double z) {return 86+t.defaults().verticalAmplitude()*(r.shape(t,x,z,1)+r.detail(t,x,z,1));}
}
