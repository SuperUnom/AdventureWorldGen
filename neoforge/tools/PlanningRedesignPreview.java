import io.github.luoyan.adventureworldgen.api.*;
import io.github.luoyan.adventureworldgen.config.*;
import io.github.luoyan.adventureworldgen.plan.*;
import io.github.luoyan.adventureworldgen.planner.RoadPlanner;
import io.github.luoyan.adventureworldgen.runtime.GeneratedAdventurePlan;
import io.github.luoyan.adventureworldgen.persistence.PlanV2Codec;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Production climate/roads from a frozen plan, plus a real planner-generated synthetic cliff ascent. */
public final class PlanningRedesignPreview {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("profile.json plan-directory output-directory");
        Path out=Path.of(args[2]);Files.createDirectories(out);Path dir=Path.of(args[1]);
        var config=new AdventureWorldConfigParser().parse(Files.readString(Path.of(args[0])));
        var manifest=com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("manifest.json"))).getAsJsonObject();
        byte[] bytes;try(var gzip=new java.util.zip.GZIPInputStream(Files.newInputStream(dir.resolve("plan.json.gz")))){bytes=gzip.readAllBytes();}
        var plan=GeneratedAdventurePlan.restore(config,new PlanV2Codec().decode(bytes,new ContentId(manifest.get("profile").getAsString()),manifest.get("input_sha256").getAsString()));
        int size=600;var picture=new BufferedImage(size*2,660,BufferedImage.TYPE_INT_RGB);var g=picture.createGraphics();
        g.setColor(new Color(0x14232e));g.fillRect(0,0,picture.getWidth(),picture.getHeight());
        int[] temp={0x4267ac,0x56b6ce,0xf1d16e,0xd45b43},humidity={0xcaa46c,0x7faa8a,0x397c91};
        double radius=config.world().radius();
        for(int z=0;z<size;z++)for(int x=0;x<size;x++) {
            double wx=(x/(double)size*2-1)*radius,wz=(z/(double)size*2-1)*radius;var s=plan.terrainAt(wx,wz);
            int t=s.wet()?0x183850:temp[plan.climate().typeAt(wx,wz,s).ordinal()];
            int h=s.wet()?0x183850:humidity[plan.climate().humidity().typeAt(wx,wz,s).ordinal()];
            picture.setRGB(x,z+40,t);picture.setRGB(x+size,z+40,h);
        }
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,18));g.drawString("Frozen temperature / seed "+plan.seed(),15,25);g.drawString("Frozen humidity",size+15,25);
        g.setFont(new Font("SansSerif",Font.PLAIN,13));g.drawString("Cold blue -> warm red. Water navy. Production frozen calibration; no preview-only formula.",15,653);g.dispose();
        ImageIO.write(picture,"png",out.resolve("climate.png").toFile());
        RoadPreview.main(new String[]{args[0],args[1],out.resolve("roads.png").toString()});
        var cliffConfig=new AdventureWorldConfigParser().parse("""
            {"world":{"radius":256},"spawn":{"biome":"test:plain"},"biomes":{"filler":["test:plain"],
             "required":[{"id":"test:end","adventure_level":1,"road":{"enabled":true,"required":true}}]},
             "roads":{"enabled":true,"maximum_grade":1,"loop_budget_fraction":0}}
            """);
        MacroTerrain cliff=(x,z)->new MacroSample(x>=8?124:64,Double.NaN,WaterKind.NONE,false,"r","mountains","test");
        var patch=new PlannedBiomePatch("end",new ContentId("test:end"),1,12,80,16,84);
        var road=new RoadPlanner(7331,cliffConfig,cliff).plan(new AdventurePlanView.SpawnPosition(.5,65,.5,0),List.of(patch),List.of(),StructurePlanningCatalog.fromIds(List.of()),(x,z)->patch.contains(x,z)?patch.biomeId():new ContentId("test:plain"));
        if(road.columns().stream().noneMatch(c->c.kind()==RoadPlan.Kind.BOARDWALK))throw new IllegalStateException("synthetic cliff did not produce a boardwalk");
        Files.writeString(out.resolve("boardwalk.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(road));
        var side=new BufferedImage(1100,450,BufferedImage.TYPE_INT_RGB);g=side.createGraphics();g.setColor(new Color(0x15252b));g.fillRect(0,0,1100,450);
        g.setFont(new Font("SansSerif",Font.BOLD,18));g.setColor(Color.WHITE);g.drawString("Planner-generated cliff boardwalk / z-height view",25,30);
        int minZ=road.columns().stream().mapToInt(RoadPlan.Column::z).min().orElse(0)-8,maxZ=road.columns().stream().mapToInt(RoadPlan.Column::z).max().orElse(128)+8;
        double scale=1000.0/(maxZ-minZ);g.setColor(new Color(0x3d5652));g.fillRect(45,60,1010,310);
        g.setColor(new Color(0xb9c9b1));g.drawString("Mountain beside deck (natural top y=124)",55,75);
        for(var beam:road.supports())if(beam.kind()==RoadPlan.SupportKind.BEAM){g.setColor(new Color(0x829381));int px=45+(int)((beam.minZ()-minZ)*scale),py=370-(beam.minY()-63)*4;g.drawLine(px,py,px,py+6);}
        for(var c:road.columns()){g.setColor(new Color(0xf5d28a));g.fillRect(45+(int)((c.z()-minZ)*scale),370-(c.deckY()-63)*4,3,3);}
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.PLAIN,14));g.drawString("Natural valley y=64; decks retain air below. Side projection, not a Minecraft screenshot.",25,415);g.dispose();
        ImageIO.write(side,"png",out.resolve("boardwalk-side.png").toFile());
        System.out.println("PREVIEW routes="+road.routes().size()+" columns="+road.columns().size()+" supports="+road.supports().size());
    }
}
