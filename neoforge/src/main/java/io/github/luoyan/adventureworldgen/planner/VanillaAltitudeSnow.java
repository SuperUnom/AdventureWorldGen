package io.github.luoyan.adventureworldgen.planner;

import io.github.luoyan.adventureworldgen.config.ContentId;
import java.util.Random;

/** Vanilla 1.21.1's height-adjusted precipitation for temperate windswept and taiga biomes.
 * The one-octave simplex field uses the native permutation and float arithmetic; the planner
 * stays usable without Minecraft registries. GameTests compare this against real Biome instances.
 */
public final class VanillaAltitudeSnow {
    private VanillaAltitudeSnow() {}
    private static final double SKEW=(Math.sqrt(3)-1)/2,UNSKEW=(3-Math.sqrt(3))/6;
    private static final int[][] GRADIENT={{1,1},{-1,1},{1,-1},{-1,-1},{1,0},{-1,0},{1,0},{-1,0},{0,1},{0,-1},{0,1},{0,-1}};
    private static final int[] PERMUTATION=permutation();
    public static boolean applies(ContentId id) {
        return band(id)>=0;
    }
    public static int band(ContentId id) {
        return switch(id.value()) {
            case "minecraft:windswept_hills","minecraft:windswept_forest","minecraft:windswept_gravelly_hills"->0;
            case "minecraft:taiga","minecraft:old_growth_spruce_taiga"->1;
            case "minecraft:old_growth_pine_taiga"->2;
            default->-1;
        };
    }
    public static boolean snowy(int x,int surfaceY,int z) {
        return (bands(x,surfaceY,z)&1)!=0;
    }
    public static boolean snowy(ContentId id,int x,int surfaceY,int z) {
        int band=band(id);return band>=0&&(bands(x,surfaceY,z)&(1<<band))!=0;
    }
    public static int bands(int x,int surfaceY,int z) {
        if(surfaceY<=80)return 0;
        float noise=(float)(simplex((float)x/8.0F,(float)z/8.0F)*8.0);
        float cooling=(noise+surfaceY-80.0F)*.05F/40.0F;
        return (.2F-cooling<.15F?1:0)|(.25F-cooling<.15F?2:0)|(.3F-cooling<.15F?4:0);
    }
    private static int[] permutation() {
        var random=new Random(1234L);
        // Vanilla consumes three origin offsets even when the caller requests no origin offset.
        for(int i=0;i<3;i++)random.nextDouble();
        int[] p=new int[256];for(int i=0;i<p.length;i++)p[i]=i;
        for(int i=0;i<p.length;i++){int j=i+random.nextInt(256-i),old=p[i];p[i]=p[j];p[j]=old;}
        return p;
    }
    private static int hash(int x,int z) {return PERMUTATION[(x+PERMUTATION[z&255])&255]%12;}
    private static double corner(int gradient,double x,double z) {
        double weight=.5-x*x-z*z;if(weight<0)return 0;
        weight*=weight;return weight*weight*(GRADIENT[gradient][0]*x+GRADIENT[gradient][1]*z);
    }
    private static double simplex(double x,double z) {
        double skew=(x+z)*SKEW;
        int gx=(int)Math.floor(x+skew),gz=(int)Math.floor(z+skew);
        double unskew=(gx+gz)*UNSKEW,dx=x-(gx-unskew),dz=z-(gz-unskew);
        int sx=dx>dz?1:0,sz=1-sx;
        return 70*(corner(hash(gx,gz),dx,dz)+corner(hash(gx+sx,gz+sz),dx-sx+UNSKEW,dz-sz+UNSKEW)
                +corner(hash(gx+1,gz+1),dx-1+2*UNSKEW,dz-1+2*UNSKEW));
    }
}
