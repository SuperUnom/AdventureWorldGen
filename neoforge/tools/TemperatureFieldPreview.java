import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.PlanV2Codec;
import io.github.luoyan.adventureworldgen.terrain.ValueNoise;
import io.github.luoyan.adventureworldgen.planner.OrganicTemperatureField;
import com.google.gson.JsonParser;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import javax.imageio.ImageIO;

/** Experimental two-field climate on actual frozen terrain; does not alter the saved plan. */
public class TemperatureFieldPreview {
    static final int SIDE=900, LEFT=82, TOP=176, WIDTH=1064, HEIGHT=1240;
    static final int[] COLORS={0x4267ac,0x56b6ce,0xf1d16e,0xd45b43};
    static final Color INK=new Color(0x203344), MUTED=new Color(0x586b7a);

    static double smooth(double t) { t=Math.clamp(t,0,1);return t*t*(3-2*t); }
    // Shared along-band warp: every latitude boundary remains parallel and ordered.
    static double latitude(double x,double z,double angle,double spacing,double bend,
                           double transition,ValueNoise warp,boolean repeating) {
        double across=x*Math.cos(angle)+z*Math.sin(angle);
        double along=-x*Math.sin(angle)+z*Math.cos(angle);
        across+=bend*warp.sample(along,0);
        if(repeating) {
            // Repeat cool -> mild -> warm -> mild. Shared warp keeps every strip aligned.
            double phase=across/spacing+2;
            long cell=(long)Math.floor(phase);
            double[] levels={.5,5,9.5,5};
            int i=(int)Math.floorMod(cell,4);
            double blend=smooth((phase-cell-.5)/(transition/spacing)+.5);
            return levels[i]+(levels[(i+1)%4]-levels[i])*blend;
        }
        double t=.5;
        for(int i=-2;i<=2;i++)t+=1.8*smooth((across-i*spacing)/transition+.5);
        return t;
    }

    public static void main(String[] args)throws Exception {
        if(args.length<2)throw new IllegalArgumentException("plan directory, output directory, [angle degrees=32], [spacing=900], [lapse=0.025], [temperature offset=0], [mountain height=110], [mountain lapse=lapse], [latitude contrast=1], [latitude mode=ordered|repeating|organic], [bend=spacing*0.16], [warp scale=spacing*2.4]");
        Path source=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
        double degrees=args.length>2?Double.parseDouble(args[2]):32;
        double spacing=args.length>3?Double.parseDouble(args[3]):900;
        double lapse=args.length>4?Double.parseDouble(args[4]):.025;
        double offset=args.length>5?Double.parseDouble(args[5]):0;
        double mountainHeight=args.length>6?Double.parseDouble(args[6]):110;
        double mountainLapse=args.length>7?Double.parseDouble(args[7]):lapse;
        double contrast=args.length>8?Double.parseDouble(args[8]):1;
        String mode=args.length>9?args[9]:"ordered";
        if(!mode.equals("ordered")&&!mode.equals("repeating")&&!mode.equals("organic"))throw new IllegalArgumentException("latitude mode must be ordered, repeating or organic");
        boolean repeating=mode.equals("repeating");
        boolean organic=mode.equals("organic");
        double bend=args.length>10?Double.parseDouble(args[10]):spacing*.16;
        double warpScale=args.length>11?Double.parseDouble(args[11]):spacing*2.4;
        if(!Double.isFinite(degrees)||!Double.isFinite(spacing)||!Double.isFinite(lapse)||!Double.isFinite(offset)
                ||!Double.isFinite(mountainHeight)||!Double.isFinite(mountainLapse)||!Double.isFinite(contrast)
                ||!Double.isFinite(bend)||!Double.isFinite(warpScale)||bend<0||warpScale<=0||contrast<=0||spacing<=0||lapse<0||mountainHeight<76||mountainLapse<lapse)
            throw new IllegalArgumentException("finite parameters, positive spacing, nonnegative lapse, mountain height >= 76 and mountain lapse >= lapse required");
        double angle=Math.toRadians(degrees),transition=spacing*.55;
        var config=new AdventureWorldConfigParser().parse(Files.readString(Path.of(
                "neoforge/src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
        String hash=JsonParser.parseString(Files.readString(source.resolve("manifest.json")))
                .getAsJsonObject().get("input_sha256").getAsString();
        io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan plan;
        try(var stream=new GZIPInputStream(Files.newInputStream(source.resolve("plan.json.gz")))) {
            plan=new PlanV2Codec().decode(stream.readAllBytes(),new ContentId("adventureworldgen:default"),hash,config);
        }
        System.out.println("Loaded frozen terrain, seed "+plan.seed());
        double extent=config.world().radius()+200,step=extent*2/SIDE;
        var warp=new ValueNoise(plan.seed(),"temperature-preview/latitude-bend",warpScale);
        java.util.function.DoubleBinaryOperator latitudeField=organic
                ?new OrganicTemperatureField(plan.seed(),spacing,bend,warpScale)
                :(x,z)->latitude(x,z,angle,spacing,bend,transition,warp,repeating);
        var heat=new BufferedImage(SIDE,SIDE,BufferedImage.TYPE_INT_RGB);
        var bands=new BufferedImage(SIDE,SIDE,BufferedImage.TYPE_INT_RGB);
        boolean[] land=new boolean[SIDE*SIDE];
        long[] counts=new long[4];long changed=0,total=0;
        double minimum=10,maximum=0,maxCooling=0;
        try(var data=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve("fields.bin"))))) {
            // Row-major big-endian floats: height, latitude, cooling, final temperature; byte: land.
            for(int iz=0;iz<SIDE;iz++) {
                for(int ix=0;ix<SIDE;ix++) {
                    double x=-extent+(ix+.5)*step,z=-extent+(iz+.5)*step;
                    var sample=plan.terrainAt(x,z);
                    boolean dry=sample.waterKind()!=io.github.luoyan.adventureworldgen.api.WaterKind.OCEAN;
                    double base=5+contrast*(latitudeField.applyAsDouble(x,z)-5)+offset;
                    // Reuse the existing 25% local / 55% slope / 20% mountain-mass height field.
                    double rise=Math.max(0,plan.climate().effectiveHeightAt(x,z,sample)-76);
                    double cooling=lapse*rise+(mountainLapse-lapse)*Math.max(0,rise+76-mountainHeight);
                    double value=Math.clamp(base-cooling,0,10);
                    if(!Double.isFinite(value)||cooling<0||value>Math.clamp(base,0,10)+1e-12)throw new AssertionError("invalid thermal field");
                    int band=Math.min(3,(int)(value/2.5));land[iz*SIDE+ix]=dry;
                    heat.setRGB(ix,iz,dry?continuous(value):0xe4ecf1);
                    bands.setRGB(ix,iz,dry?COLORS[band]:0xe4ecf1);
                    if(dry){counts[band]++;total++;minimum=Math.min(minimum,value);maximum=Math.max(maximum,value);
                        maxCooling=Math.max(maxCooling,cooling);if(band!=Math.clamp((int)(base/2.5),0,3))changed++;}
                    data.writeFloat((float)sample.groundSurface());data.writeFloat((float)base);
                    data.writeFloat((float)cooling);data.writeFloat((float)value);data.writeByte(dry?1:0);
                }
                if(iz%150==0)System.out.println("Sampled rows "+iz+" / "+SIDE);
            }
        }
        if(total==0)throw new AssertionError("no land sampled");
        for(long n:counts)if(n==0)throw new AssertionError("missing temperature class");
        // Ordered mode is monotone; repeated strips must join continuously and repeat exactly.
        for(int along=-3000;along<=3000;along+=600) {
            double previous=-1;
            for(int across=-5000;across<=5000;across+=4) {
                double x=across*Math.cos(angle)-along*Math.sin(angle),z=across*Math.sin(angle)+along*Math.cos(angle);
                double t=latitudeField.applyAsDouble(x,z);
                if(t<.5-1e-10||t>9.5+1e-10)throw new AssertionError("latitude outside range");
                if(organic) {
                    if(t!=latitudeField.applyAsDouble(x,z))throw new AssertionError("unstable organic field");
                    if(Math.abs(t-latitudeField.applyAsDouble(x+.001,z))>.01
                            ||Math.abs(t-latitudeField.applyAsDouble(x,z+.001))>.01)throw new AssertionError("organic discontinuity");
                } else if(repeating) {
                    double next=latitude(x+4*spacing*Math.cos(angle),z+4*spacing*Math.sin(angle),angle,spacing,bend,transition,warp,true);
                    if(Math.abs(t-next)>1e-9)throw new AssertionError("latitude period mismatch");
                    if(previous>=0&&Math.abs(t-previous)>4*1.5*4.5/transition+1e-9)throw new AssertionError("latitude discontinuity");
                } else if(t+1e-10<previous)throw new AssertionError("latitude order reversed");
                previous=t;
            }
        }
        outline(heat,land);outline(bands,land);
        var a=frame(heat,false,plan.seed(),extent,degrees,spacing,bend,lapse,offset,mountainHeight,mountainLapse,contrast,repeating,organic,counts,total);
        var b=frame(bands,true,plan.seed(),extent,degrees,spacing,bend,lapse,offset,mountainHeight,mountainLapse,contrast,repeating,organic,counts,total);
        ImageIO.write(a,"png",out.resolve("temperature.png").toFile());
        ImageIO.write(b,"png",out.resolve("temperature-bands.png").toFile());
        var comparison=new BufferedImage(WIDTH*2,HEIGHT,BufferedImage.TYPE_INT_RGB);
        var g=comparison.createGraphics();g.drawImage(a,0,0,null);g.drawImage(b,WIDTH,0,null);g.dispose();
        ImageIO.write(comparison,"png",out.resolve("comparison.png").toFile());
        String metadata=String.format(Locale.ROOT,"""
                {"seed":%d,"side":%d,"extent":%.1f,"angle_degrees":%s,"spacing":%.1f,
                "bend":%.1f,"transition_width":%.1f,"lapse":%.4f,"temperature_offset":%.2f,"height_reference":76,
                "mountain_height":%.1f,"mountain_lapse":%.4f,"latitude_contrast":%.3f,
                "latitude_mode":"%s","latitude_period":%.1f,"warp_scale":%.1f,
                "band_counts":[%d,%d,%d,%d],"land_samples":%d,"min":%.4f,"max":%.4f,
                "max_cooling":%.4f,"altitude_changed_band_fraction":%.4f,
                "binary_layout":"row-major big-endian: float height, latitude, cooling, temperature; unsigned byte land"}
                """,plan.seed(),SIDE,extent,organic?"null":Double.toString(degrees),spacing,bend,organic?0:transition,lapse,offset,mountainHeight,mountainLapse,contrast,mode,repeating?4*spacing:0,warpScale,
                counts[0],counts[1],counts[2],counts[3],total,minimum,maximum,maxCooling,changed/(double)total);
        Files.writeString(out.resolve("parameters.json"),metadata);
        System.out.println(metadata);System.out.println("PASS finite, altitude cooling, latitude mode "+mode+", four classes; "+out);
    }

    static int continuous(double value) {
        double[] stops={0,1.25,3.75,6.25,8.75,10};
        int[] colors={0x273b70,COLORS[0],COLORS[1],COLORS[2],COLORS[3],0x9c302f};
        int i=0;while(i<stops.length-2&&value>stops[i+1])i++;
        double f=Math.clamp((value-stops[i])/(stops[i+1]-stops[i]),0,1);int result=0;
        for(int shift:new int[]{16,8,0})result|=(int)Math.round(((colors[i]>>shift)&255)*(1-f)+((colors[i+1]>>shift)&255)*f)<<shift;
        return result;
    }
    static void outline(BufferedImage image,boolean[] land) {
        for(int z=1;z<SIDE-1;z++)for(int x=1;x<SIDE-1;x++) {
            int i=z*SIDE+x;
            if(land[i]&&(!land[i-1]||!land[i+1]||!land[i-SIDE]||!land[i+SIDE]))image.setRGB(x,z,0x687a86);
        }
    }
    static void label(Graphics2D g,String text,int x,int y,int size,boolean bold,Color color) {
        g.setFont(new Font("SansSerif",bold?Font.BOLD:Font.PLAIN,size));g.setColor(color);g.drawString(text,x,y);
    }
    static BufferedImage frame(BufferedImage map,boolean bands,long seed,double extent,double angle,
                               double spacing,double bend,double lapse,double offset,double mountainHeight,double mountainLapse,double contrast,boolean repeating,boolean organic,long[] counts,long total) {
        var image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xf7f9fb));g.fillRect(0,0,WIDTH,HEIGHT);
        label(g,bands?"02 / 温度带图":"01 / 连续温度图",LEFT,54,34,true,INK);
        label(g,bands?"同一温度场分成四档，观察山脚、山腰与山峰":organic?"二维自然温度场 ＋ 地形海拔降温":String.format(Locale.ROOT,"%s（扭曲幅度 %.0f 格）＋ 海拔降温",repeating?"重复冷暖条带":"旋转纬度阶梯",bend),LEFT,94,23,false,INK);
        label(g,organic?String.format(Locale.ROOT,"种子 %d  ·  特征尺度 %.0f 格  ·  二维多尺度扭曲",seed,spacing)
                :String.format(Locale.ROOT,"种子 %d  ·  带宽 %.0f 格  ·  %s +X 转 +Z %.0f°",seed,spacing,repeating?"跨带方向":"暖向",angle),LEFT,129,19,false,MUTED);
        String thermal=mountainLapse==lapse
                ?String.format(Locale.ROOT,"纬度温度 %+.1f  ·  海拔每百格降温 %.1f  ·  温度指数 0–10",offset,lapse*100)
                :String.format(Locale.ROOT,"基础温度 %+.1f  ·  每百格降温 %.1f → %.1f（Y%.0f 起）",offset,lapse*100,mountainLapse*100,mountainHeight);
        label(g,thermal,LEFT,158,19,false,MUTED);
        g.drawImage(map,LEFT,TOP,null);
        g.setColor(new Color(0x9baab5));g.drawRect(LEFT,TOP,SIDE,SIDE);
        for(int v=-3000;v<=3000;v+=1500) {
            int p=(int)Math.round((v+extent)/(2*extent)*SIDE);
            g.drawLine(LEFT+p,TOP+SIDE,LEFT+p,TOP+SIDE+5);
            label(g,Integer.toString(v),LEFT+p-22,TOP+SIDE+25,15,false,MUTED);
            label(g,Integer.toString(v),18,TOP+p+5,15,false,MUTED);
        }
        label(g,"X →",LEFT+SIDE-12,TOP+SIDE+47,16,false,MUTED);
        label(g,"Z ↓",19,TOP-8,16,false,MUTED);
        int center=LEFT+SIDE/2,cy=TOP+SIDE/2;
        g.setColor(new Color(0x203344));g.setStroke(new BasicStroke(4));g.drawLine(center-6,cy,center+6,cy);g.drawLine(center,cy-6,center,cy+6);
        g.setColor(Color.WHITE);g.setStroke(new BasicStroke(2));g.drawLine(center-6,cy,center+6,cy);g.drawLine(center,cy-6,center,cy+6);
        int ly=1130;
        if(bands) {
            String[] names={"严寒 [0,2.5)","寒冷 [2.5,5)","温和 [5,7.5)","炎热 [7.5,10]"};
            for(int i=0;i<4;i++) {
                int x=LEFT+i*230;g.setColor(new Color(COLORS[i]));g.fillRect(x,ly,24,24);
                label(g,names[i],x+32,ly+19,17,false,INK);
                label(g,String.format(Locale.ROOT,"陆地 %.1f%%",counts[i]*100.0/total),x+32,ly+44,16,false,MUTED);
            }
        } else {
            for(int x=0;x<SIDE;x++){g.setColor(new Color(continuous(x*10.0/(SIDE-1))));g.drawLine(LEFT+x,ly,LEFT+x,ly+20);}
            for(int i=0;i<=4;i++)label(g,String.format(Locale.ROOT,"%.1f",i*2.5),LEFT+i*SIDE/4-12,ly+44,16,false,MUTED);
        }
        label(g,"浅灰蓝：外海  ·  十字：原点  ·  使用已保存的真实地形；实验预览",LEFT,1214,18,false,MUTED);
        g.dispose();return image;
    }
}
