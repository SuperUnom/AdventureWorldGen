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

/** RoadPreview <output.png> for synthetic cases; or <profile.json> <plan-dir> <output.png> for a frozen world. */
public final class RoadPreview {
    record Panel(String title,MacroTerrain terrain,RoadPlan roads,double minX,double minZ,double spanX,double spanZ) {}
    static MacroSample land(double y){return new MacroSample(y,Double.NaN,WaterKind.NONE,false,"plain","plains","test");}
    public static void main(String[] args)throws Exception {
        var panels=new java.util.ArrayList<Panel>();Path output;
        if(args.length==1) {
            output=Path.of(args[0]);
            var config=new AdventureWorldConfigParser().parse("""
                {"world":{"radius":1024},"spawn":{"biome":"test:plains"},"biomes":{"filler":["test:plains"],
                 "required":[{"id":"test:end","adventure_level":1,"road":{"enabled":true,"required":true}}]},"roads":{"enabled":true}}
                """);
            var patch=new PlannedBiomePatch("end",new ContentId("test:end"),1,478,-2,486,6);
            String[] titles={"PLAIN / long gentle bends","HILLS / continuous grades","RIVER / straight bridge, curved approaches","OBSTACLE / bounded detour"};
            MacroTerrain[] terrains={(x,z)->land(64),(x,z)->land(64+12*StrictMath.sin(x/100)),
                    (x,z)->x>220&&x<242?new MacroSample(59,63,WaterKind.RIVER,false,"river","plains","test"):land(64),
                    (x,z)->x>180&&x<260&&Math.abs(z)<32?new MacroSample(64,Double.NaN,WaterKind.NONE,true,"obstacle","plains","test"):land(64)};
            for(int i=0;i<terrains.length;i++) {
                var road=new RoadPlanner(472,config,terrains[i]).plan(new AdventurePlanView.SpawnPosition(.5,64,.5,0),List.of(patch),List.of(),StructurePlanningCatalog.fromIds(List.of()),(x,z)->patch.biomeId());
                double minZ=Math.min(-62.1923076923,road.columns().stream().mapToInt(RoadPlan.Column::z).min().orElse(0)-12);
                double maxZ=Math.max(62.1923076923,road.columns().stream().mapToInt(RoadPlan.Column::z).max().orElse(0)+12);
                panels.add(new Panel(titles[i],terrains[i],road,-24,minZ,528,maxZ-minZ));
                System.out.println(titles[i]+": routes="+road.routes().size()+", length="+road.routes().stream().mapToDouble(RoadPlan.Route::length).sum()+", columns="+road.columns().size()+", samples="+road.operations());
            }
        } else if(args.length==3) {
            output=Path.of(args[2]);var config=new AdventureWorldConfigParser().parse(Files.readString(Path.of(args[0])));
            Path dir=Path.of(args[1]);var manifest=com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("manifest.json"))).getAsJsonObject();
            byte[] bytes;try(var gzip=new java.util.zip.GZIPInputStream(Files.newInputStream(dir.resolve("plan.json.gz")))){bytes=gzip.readAllBytes();}
            var plan=GeneratedAdventurePlan.restore(config,new PlanV2Codec().decode(bytes,new ContentId(manifest.get("profile").getAsString()),manifest.get("input_sha256").getAsString()));
            double radius=config.world().radius();double minX=-radius,minZ=-radius,spanX=2*radius,spanZ=2*radius;
            if(!plan.roads().columns().isEmpty()) {
                minX=plan.roads().columns().stream().mapToInt(RoadPlan.Column::x).min().orElse(0)-32;
                minZ=plan.roads().columns().stream().mapToInt(RoadPlan.Column::z).min().orElse(0)-32;
                spanX=plan.roads().columns().stream().mapToInt(RoadPlan.Column::x).max().orElse(0)+32-minX;
                spanZ=plan.roads().columns().stream().mapToInt(RoadPlan.Column::z).max().orElse(0)+32-minZ;
            }
            panels.add(new Panel("FROZEN WORLD / seed "+plan.seed()+" / road extent",plan::terrainAt,plan.roads(),minX,minZ,spanX,spanZ));
        } else throw new IllegalArgumentException("RoadPreview <output.png> OR <profile.json> <plan-dir> <output.png>");
        int width=1100,panelHeight=405;var image=new BufferedImage(width,panelHeight*panels.size(),BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x15252b));g.fillRect(0,0,width,image.getHeight());
        for(int index=0;index<panels.size();index++) {
            var panel=panels.get(index);int left=30,top=index*panelHeight+45,mapWidth=1040,mapHeight=245;
            double scale=Math.min(mapWidth/panel.spanX(),mapHeight/panel.spanZ());
            double viewX=panel.minX()+panel.spanX()/2-mapWidth/scale/2,viewZ=panel.minZ()+panel.spanZ()/2-mapHeight/scale/2;
            for(int pz=0;pz<mapHeight;pz++)for(int px=0;px<mapWidth;px++) {
                var s=panel.terrain().sample(viewX+px/scale,viewZ+pz/scale);
                int shade=(int)Math.clamp(95+(s.groundSurface()-64)*2,40,150);
                Color color=s.hazardous()?new Color(0x604d50):s.wet()?new Color(0x4389aa):new Color(shade,shade+25,shade-12);
                image.setRGB(left+px,top+pz,color.getRGB());
            }
            g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,18));g.drawString(panel.title(),left,top-15);
            double sx=scale,sz=scale;
            for(var c:panel.roads().columns()) {
                g.setColor(c.bridge()?new Color(0xd48c50):c.shoulder()?new Color(0xa99975):new Color(0xefddb3));
                int x=left+(int)((c.x()-viewX)*sx),z=top+(int)((c.z()-viewZ)*sz);
                g.fillRect(x,z,Math.max(1,(int)Math.ceil(sx)),Math.max(1,(int)Math.ceil(sz)));
            }
            for(var node:panel.roads().nodes()) {int x=left+(int)((node.x()-viewX)*sx),z=top+(int)((node.z()-viewZ)*sz);g.setColor(new Color(0xecc961));g.fillOval(x-5,z-5,10,10);}
            g.setFont(new Font("SansSerif",Font.PLAIN,13));g.setColor(new Color(0xb6c5cc));
            g.drawString("Frozen construction columns | beige: road | orange: bridge | gold: destination | profile below: deck height",left,top+264);
            if(!panel.roads().routes().isEmpty()) {
                var route=panel.roads().routes().getFirst();var byColumn=new java.util.HashMap<Long,RoadPlan.Column>();panel.roads().columns().forEach(c->byColumn.put(RoadPlan.key(c.x(),c.z()),c));
                double min=panel.roads().columns().stream().mapToInt(RoadPlan.Column::deckY).min().orElse(0)-2,max=panel.roads().columns().stream().mapToInt(RoadPlan.Column::deckY).max().orElse(1)+2;
                g.setColor(new Color(0xefddb3));int previousX=-1,previousY=-1;double distance=0;
                for(int i=1;i<route.points().size();i++) {
                    var a=route.points().get(i-1);var b=route.points().get(i);double length=Math.hypot(b.x()-a.x(),b.z()-a.z());int steps=Math.max(1,(int)Math.ceil(length));
                    for(int k=0;k<steps;k++){double t=k/(double)steps;var cell=byColumn.get(RoadPlan.key((int)Math.floor(a.x()+(b.x()-a.x())*t),(int)Math.floor(a.z()+(b.z()-a.z())*t)));if(cell==null)continue;
                        int x=left+(int)((distance+length*t)/route.length()*mapWidth),y=top+325-(int)((cell.deckY()-min)/(max-min)*40);
                        if(previousX>=0)g.drawLine(previousX,previousY,x,y);previousX=x;previousY=y;
                    }distance+=length;
                }
                g.setColor(new Color(0xb6c5cc));g.drawString(String.format(java.util.Locale.ROOT,"%.1f blocks | %d columns | %d terrain samples",route.length(),panel.roads().columns().size(),panel.roads().operations()),left,top+345);
            }
        }
        g.dispose();Files.createDirectories(output.toAbsolutePath().getParent());ImageIO.write(image,"png",output.toFile());
        System.out.println(output.toAbsolutePath());
    }
}
