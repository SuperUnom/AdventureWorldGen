import io.github.luoyan.adventureworldgen.runtime.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.persistence.*;
import io.github.luoyan.adventureworldgen.plan.ContentId;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import com.google.gson.JsonParser;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Actual frozen-plan queries, not an in-game screenshot. */
public class PlannerMapPreview {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]), output=Path.of(args[1]);
        var config=new AdventureWorldConfigParser().parse(Files.newBufferedReader(Path.of(
                "src/main/resources/data/adventureworldgen/adventureworldgen/profiles/default.json")));
        String hash=JsonParser.parseString(Files.readString(directory.resolve("manifest.json"))).getAsJsonObject().get("input_sha256").getAsString();
        GeneratedAdventurePlan plan;
        try(var input=new GZIPInputStream(Files.newInputStream(directory.resolve("plan.json.gz")))) {
            plan=GeneratedAdventurePlan.restore(config,new PlanV2Codec().decode(input.readAllBytes(),new ContentId("adventureworldgen:default"),hash));
        }
        int side=600,margin=20,top=96,width=side*2+margin*3,height=side+top+54;
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setColor(new Color(0x152331));g.fillRect(0,0,width,height);
        String revision=args.length>2?args[2]:"r12";
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,24));g.drawString(revision+"  |  Frozen biome allocation and planning temperature",margin,32);
        g.setFont(new Font("SansSerif",Font.PLAIN,16));g.drawString("Seed "+plan.seed()+"  |  6200 x 6200 blocks  |  White cross: spawn",margin,59);
        g.drawString("Biome layout",margin,85);g.drawString("Planning temperature: cold 0 -> hot 10",side+margin*2,85);
        for(int z=0;z<side;z++)for(int x=0;x<side;x++) {
            int wx=-3100+x*6200/side,wz=-3100+z*6200/side;
            var id=plan.biomeAt(wx,64,wz);String name=id.value();
            int color=color(name),heat;
            if(name.equals("minecraft:ocean"))heat=0x234760;
            else if(name.endsWith("river"))heat=0x479ABD;
            else heat=Color.HSBtoRGB((float)((10-config.biomes().temperature(id))*0.061),0.65f,0.92f)&0xffffff;
            image.setRGB(margin+x,top+z,color);image.setRGB(side+margin*2+x,top+z,heat);
        }
        g.setColor(Color.WHITE);
        for(int offset:new int[]{margin,side+margin*2}) {
            g.drawLine(offset+side/2-5,top+side/2,offset+side/2+5,top+side/2);
            g.drawLine(offset+side/2,top+side/2-5,offset+side/2,top+side/2+5);
        }
        g.setFont(new Font("SansSerif",Font.PLAIN,14));
        g.drawString("Blue: water  |  Green: woodland / plains  |  Sand: desert  |  Gray / white: mountains / snow",margin,height-27);
        g.drawString("Rendered from saved plan data; this is not an in-game screenshot.",margin,height-8);
        g.dispose();Files.createDirectories(output.getParent());ImageIO.write(image,"png",output.toFile());
        System.out.println(output);
    }
    private static int color(String id) {
        String name=id.substring(id.indexOf(':')+1);
        if(name.equals("ocean"))return 0x234760;
        if(name.endsWith("river"))return 0x479ABD;
        if(name.equals("desert"))return 0xD7C787;
        if(name.contains("snowy")||name.equals("frozen_peaks")||name.equals("grove"))return 0xDCE8DD;
        if(name.contains("peaks")||name.equals("windswept_gravelly_hills"))return 0x858A93;
        if(name.contains("savanna"))return 0x94914D;
        if(name.contains("jungle")||name.equals("mangrove_swamp"))return 0x287A42;
        if(name.contains("forest")||name.contains("taiga"))return 0x38684A;
        if(name.equals("swamp"))return 0x496C59;
        if(name.equals("cherry_grove"))return 0xB9889F;
        return 0x88A865;
    }
}
